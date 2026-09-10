package com.paytm.wallet.service.transfer;

import com.paytm.wallet.domain.Transfer;
import com.paytm.wallet.idempotency.RequestFingerprint;
import com.paytm.wallet.observability.WalletMetrics;
import com.paytm.wallet.service.DomainExceptions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit test with a hand-rolled fake {@link JdbcTemplate} (the local JDK
 * can't run the inline mock maker).
 */
class ConditionalUpdateEngineTest {

    private final List<String> updates = new ArrayList<>();
    private int nextUpdateResult = 1;
    private Transfer preexisting; // what findByKey(...) returns; null = miss

    private final JdbcTemplate fakeJdbc = new JdbcTemplate() {
        @Override
        public void query(String sql, RowCallbackHandler rch, Object... args) {
            // the FOR UPDATE lock — nothing to hand back
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            return preexisting == null ? List.of() : (List<T>) List.of(preexisting); // findByKey
        }

        @Override
        public int update(String sql, Object... args) {
            updates.add(sql);
            return sql.contains("balance_paise - ?") ? nextUpdateResult : 1;
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> type, Object... args) {
            return type.cast(0L); // balanceOf(...) for the debited/credited events
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T queryForObject(String sql, RowMapper<T> rowMapper, Object... args) {
            return (T) new Transfer(UUID.randomUUID(), (UUID) args[0], (UUID) args[1],
                    (long) args[2], (String) args[3], (String) args[4],
                    Transfer.Status.valueOf((String) args[5]), (String) args[6], Instant.now());
        }
    };

    private final PlatformTransactionManager noopTx = new PlatformTransactionManager() {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition d) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus s) {
        }

        @Override
        public void rollback(TransactionStatus s) {
        }
    };

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ConditionalUpdateEngine engine =
            new ConditionalUpdateEngine(fakeJdbc, noopTx, new WalletMetrics(registry));

    private static final UUID FROM = UUID.randomUUID();
    private static final UUID TO = UUID.randomUUID();

    private static TransferRequest req(long amount) {
        return new TransferRequest(FROM, TO, amount, "key-1");
    }

    private double counter(String name) {
        return registry.get(name).counter().count();
    }

    @Test
    void insufficient_funds_declines_without_crediting() {
        nextUpdateResult = 0; // conditional debit affects no rows

        var result = engine.execute(req(500), "cid");

        assertThat(result.transfer().status()).isEqualTo(Transfer.Status.DECLINED);
        assertThat(result.transfer().declineReason()).isEqualTo("insufficient_funds");
        assertThat(updates).hasSize(1);
        assertThat(updates.get(0)).contains("balance_paise - ?");
        assertThat(counter("wallet.transfers.declined")).isEqualTo(1.0);
    }

    @Test
    void sufficient_funds_debits_then_credits_then_inserts_completed() {
        nextUpdateResult = 1;

        var result = engine.execute(req(30), "cid");

        assertThat(result.transfer().status()).isEqualTo(Transfer.Status.COMPLETED);
        assertThat(updates).hasSize(2);
        assertThat(updates.get(0)).contains("balance_paise - ?");
        assertThat(updates.get(1)).contains("balance_paise + ?");
        assertThat(counter("wallet.transfers.created")).isEqualTo(1.0);
    }

    @Test
    void known_key_same_body_replays_without_touching_balances() {
        preexisting = new Transfer(UUID.randomUUID(), FROM, TO, 30, "key-1",
                RequestFingerprint.of(FROM, TO, 30), Transfer.Status.COMPLETED, null, Instant.now());

        var result = engine.execute(req(30), "cid");

        assertThat(result.transfer()).isEqualTo(preexisting);
        assertThat(result.replayed()).isTrue();
        assertThat(updates).isEmpty(); // no debit, no credit
        assertThat(counter("wallet.transfers.idempotent_replay")).isEqualTo(1.0);
    }

    @Test
    void known_key_different_body_is_a_conflict() {
        preexisting = new Transfer(UUID.randomUUID(), FROM, TO, 30, "key-1",
                RequestFingerprint.of(FROM, TO, 30), Transfer.Status.COMPLETED, null, Instant.now());

        assertThatThrownBy(() -> engine.execute(req(999), "cid")) // amount differs
                .isInstanceOf(DomainExceptions.IdempotencyConflict.class);
        assertThat(updates).isEmpty();
    }
}
