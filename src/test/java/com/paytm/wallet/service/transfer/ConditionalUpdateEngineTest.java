package com.paytm.wallet.service.transfer;

import com.paytm.wallet.domain.Transfer;
import com.paytm.wallet.idempotency.RequestFingerprint;
import com.paytm.wallet.observability.WalletMetrics;
import com.paytm.wallet.service.DomainExceptions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
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

/** Pure unit test with a hand-rolled fake {@link JdbcTemplate}. */
class ConditionalUpdateEngineTest {

    private final List<String> statements = new ArrayList<>();
    private boolean overdraft = false;
    private Transfer preexisting; // findByKey(...) result; null = miss

    @SuppressWarnings("unchecked")
    private final JdbcTemplate fakeJdbc = new JdbcTemplate() {
        @Override
        public void query(String sql, RowCallbackHandler rch, Object... args) {
            // FOR UPDATE lock — nothing to hand back
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            return preexisting == null ? List.of() : (List<T>) List.of(preexisting); // findByKey
        }

        @Override
        public <T> T query(String sql, ResultSetExtractor<T> rse, Object... args) {
            statements.add(sql); // debitConditional (RETURNING)
            return (T) (overdraft ? null : Long.valueOf(900L));
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> type, Object... args) {
            statements.add(sql); // credit (RETURNING balance_paise)
            return type.cast(100L);
        }

        @Override
        public <T> T queryForObject(String sql, RowMapper<T> rowMapper, Object... args) {
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
        overdraft = true;

        var result = engine.execute(req(500));

        assertThat(result.transfer().status()).isEqualTo(Transfer.Status.DECLINED);
        assertThat(result.transfer().declineReason()).isEqualTo("insufficient_funds");
        assertThat(statements).hasSize(1);                       // the conditional debit only
        assertThat(statements.get(0)).contains("balance_paise - ?");
        assertThat(counter("wallet.transfers.declined")).isEqualTo(1.0);
    }

    @Test
    void sufficient_funds_debits_then_credits_then_inserts_completed() {
        overdraft = false;

        var result = engine.execute(req(30));

        assertThat(result.transfer().status()).isEqualTo(Transfer.Status.COMPLETED);
        assertThat(statements).hasSize(2);
        assertThat(statements.get(0)).contains("balance_paise - ?");
        assertThat(statements.get(1)).contains("balance_paise + ?");
        assertThat(counter("wallet.transfers.created")).isEqualTo(1.0);
    }

    @Test
    void known_key_same_body_replays_without_touching_balances() {
        preexisting = new Transfer(UUID.randomUUID(), FROM, TO, 30, "key-1",
                RequestFingerprint.of(FROM, TO, 30), Transfer.Status.COMPLETED, null, Instant.now());

        var result = engine.execute(req(30));

        assertThat(result.transfer()).isEqualTo(preexisting);
        assertThat(result.replayed()).isTrue();
        assertThat(statements).isEmpty();
        assertThat(counter("wallet.transfers.idempotent_replay")).isEqualTo(1.0);
    }

    @Test
    void known_key_different_body_is_a_conflict() {
        preexisting = new Transfer(UUID.randomUUID(), FROM, TO, 30, "key-1",
                RequestFingerprint.of(FROM, TO, 30), Transfer.Status.COMPLETED, null, Instant.now());

        assertThatThrownBy(() -> engine.execute(req(999)))
                .isInstanceOf(DomainExceptions.IdempotencyConflict.class);
        assertThat(statements).isEmpty();
    }
}
