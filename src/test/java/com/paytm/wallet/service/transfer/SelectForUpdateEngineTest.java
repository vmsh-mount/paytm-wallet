package com.paytm.wallet.service.transfer;

import com.paytm.wallet.domain.Transfer;
import com.paytm.wallet.observability.WalletMetrics;
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

class SelectForUpdateEngineTest {

    private long sourceBalance = 1_000L;
    private final List<String> statements = new ArrayList<>();

    @SuppressWarnings("unchecked")
    private final JdbcTemplate fakeJdbc = new JdbcTemplate() {
        @Override
        public void query(String sql, RowCallbackHandler rch, Object... args) { /* FOR UPDATE lock */ }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rm, Object... args) {
            return List.of(); // findByKey miss
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> type, Object... args) {
            if (sql.startsWith("SELECT")) {
                return type.cast(sourceBalance); // balanceOf(from) — the pre-check read
            }
            statements.add(sql); // debit / credit RETURNING
            return type.cast(sourceBalance);
        }

        @Override
        public <T> T queryForObject(String sql, RowMapper<T> rm, Object... args) {
            return (T) new Transfer(UUID.randomUUID(), (UUID) args[0], (UUID) args[1],
                    (long) args[2], (String) args[3], (String) args[4],
                    Transfer.Status.valueOf((String) args[5]), (String) args[6], Instant.now());
        }
    };

    private final PlatformTransactionManager noopTx = new PlatformTransactionManager() {
        @Override public TransactionStatus getTransaction(TransactionDefinition d) { return new SimpleTransactionStatus(); }
        @Override public void commit(TransactionStatus s) { }
        @Override public void rollback(TransactionStatus s) { }
    };

    private final SelectForUpdateEngine engine =
            new SelectForUpdateEngine(fakeJdbc, noopTx, new WalletMetrics(new SimpleMeterRegistry()));

    private static TransferRequest req(long amount) {
        return new TransferRequest(UUID.randomUUID(), UUID.randomUUID(), amount, "k");
    }

    @Test
    void insufficient_balance_declines_before_any_write() {
        sourceBalance = 50;

        var outcome = engine.execute(req(200));

        assertThat(outcome.transfer().status()).isEqualTo(Transfer.Status.DECLINED);
        assertThat(outcome.transfer().declineReason()).isEqualTo("insufficient_funds");
        assertThat(statements).as("no balance UPDATE issued").isEmpty();
    }

    @Test
    void sufficient_balance_issues_two_updates() {
        sourceBalance = 500;

        var outcome = engine.execute(req(200));

        assertThat(outcome.transfer().status()).isEqualTo(Transfer.Status.COMPLETED);
        assertThat(statements).hasSize(2); // debit + credit
        assertThat(statements).allMatch(s -> s.startsWith("UPDATE wallets"));
    }
}
