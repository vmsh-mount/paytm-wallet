package com.paytm.wallet.service.transfer;

import com.paytm.wallet.domain.Transfer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit test with a hand-rolled fake {@link JdbcTemplate} (the local JDK
 * can't run the inline mock maker). Focus: the {@code rowsAffected == 0} branch
 * declines without issuing the credit.
 */
class ConditionalUpdateEngineTest {

    private final List<String> updates = new ArrayList<>();
    private int nextUpdateResult = 1;
    private Transfer inserted;

    private final JdbcTemplate fakeJdbc = new JdbcTemplate() {
        @Override
        public void query(String sql, RowCallbackHandler rch, Object... args) {
            // the FOR UPDATE lock — nothing to hand back
        }

        @Override
        public int update(String sql, Object... args) {
            updates.add(sql);
            return sql.contains("- ?") ? nextUpdateResult : 1; // debit is conditional; credit always 1
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T queryForObject(String sql, RowMapper<T> rowMapper, Object... args) {
            // args: from, to, amount, key, fingerprint, status, declineReason
            inserted = new Transfer(UUID.randomUUID(), (UUID) args[0], (UUID) args[1],
                    (long) args[2], (String) args[3], (String) args[4],
                    Transfer.Status.valueOf((String) args[5]), (String) args[6], Instant.now());
            return (T) inserted;
        }
    };

    private final PlatformTransactionManager noopTx = new PlatformTransactionManager() {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    };

    private final ConditionalUpdateEngine engine = new ConditionalUpdateEngine(fakeJdbc, noopTx);

    private static TransferRequest req(long amount) {
        return new TransferRequest(UUID.randomUUID(), UUID.randomUUID(), amount, "key-1");
    }

    @Test
    void insufficient_funds_declines_without_crediting() {
        nextUpdateResult = 0; // conditional debit affects no rows

        Transfer result = engine.execute(req(500), "cid");

        assertThat(result.status()).isEqualTo(Transfer.Status.DECLINED);
        assertThat(result.declineReason()).isEqualTo("insufficient_funds");
        assertThat(updates).hasSize(1); // debit attempted, credit NOT issued
        assertThat(updates.get(0)).contains("balance_paise - ?");
    }

    @Test
    void sufficient_funds_debits_then_credits_then_inserts_completed() {
        nextUpdateResult = 1;

        Transfer result = engine.execute(req(30), "cid");

        assertThat(result.status()).isEqualTo(Transfer.Status.COMPLETED);
        assertThat(result.declineReason()).isNull();
        assertThat(updates).hasSize(2);
        assertThat(updates.get(0)).contains("balance_paise - ?");
        assertThat(updates.get(1)).contains("balance_paise + ?");
    }
}
