package com.paytm.wallet.service.transfer;

import com.paytm.wallet.domain.Transfer;
import com.paytm.wallet.observability.WalletMetrics;
import com.paytm.wallet.service.DomainExceptions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotSerializeTransactionException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SerializableEngineTest {

    private int failuresRemaining = 0;

    private final JdbcTemplate fakeJdbc = new JdbcTemplate() {
        @Override
        public void query(String sql, RowCallbackHandler rch, Object... args) {
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rm, Object... args) {
            return List.of(); // findByKey miss
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> type, Object... args) {
            return type.cast(1_000L); // balanceOf
        }

        @Override
        public int update(String sql, Object... args) {
            if (failuresRemaining-- > 0) {
                throw new CannotSerializeTransactionException("conflict",
                        new SQLException("could not serialize access", "40001"));
            }
            return 1;
        }

        @Override
        @SuppressWarnings("unchecked")
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

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private SerializableEngine engine(int maxRetries) {
        return new SerializableEngine(fakeJdbc, noopTx, new WalletMetrics(registry), maxRetries);
    }

    private static TransferRequest req() {
        return new TransferRequest(UUID.randomUUID(), UUID.randomUUID(), 100, "k");
    }

    @Test
    void retries_40001_and_eventually_succeeds() {
        failuresRemaining = 2;

        var outcome = engine(5).execute(req(), "cid");

        assertThat(outcome.transfer().status()).isEqualTo(Transfer.Status.COMPLETED);
        assertThat(registry.get("wallet.transfer.retries").tag("engine", "serializable").counter().count())
                .isEqualTo(2.0);
    }

    @Test
    void exhausted_retries_raise_503_not_500() {
        failuresRemaining = 100;

        assertThatThrownBy(() -> engine(3).execute(req(), "cid"))
                .isInstanceOf(DomainExceptions.SerializationExhausted.class);
        assertThat(registry.get("wallet.transfer.retries").counter().count()).isEqualTo(3.0);
    }
}
