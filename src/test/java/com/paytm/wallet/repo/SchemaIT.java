package com.paytm.wallet.repo;

import com.paytm.wallet.support.AbstractPostgresIT;
import com.paytm.wallet.support.TestSeed;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Flyway builds V1, and each CHECK / UNIQUE / FK rejects its violation. */
class SchemaIT extends AbstractPostgresIT {

    @Test
    void flyway_created_both_tables_and_the_named_constraints() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = 'public' ORDER BY table_name", String.class);
        assertThat(tables).contains("wallets", "transfers", "flyway_schema_history");

        List<String> constraints = jdbc.queryForList(
                "SELECT conname FROM pg_constraint "
                + "WHERE conrelid IN ('wallets'::regclass, 'transfers'::regclass)", String.class);
        assertThat(constraints).contains(
                "wallets_user_id_key",
                "transfers_idempotency_key_key",
                "transfers_distinct_wallets",
                "transfers_decline_reason_iff_declined");
    }

    @Test
    void negative_balance_is_rejected() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO wallets (user_id, balance_paise) VALUES ('x', -1)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicate_user_id_is_rejected() {
        TestSeed.wallet(jdbc, "dup", 0);
        assertThatThrownBy(() -> TestSeed.wallet(jdbc, "dup", 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicate_idempotency_key_is_rejected() {
        UUID a = TestSeed.wallet(jdbc, "a", 100);
        UUID b = TestSeed.wallet(jdbc, "b", 0);
        TestSeed.transfer(jdbc, a, b, 10, "same-key", "fp1");
        assertThatThrownBy(() -> TestSeed.transfer(jdbc, a, b, 20, "same-key", "fp2"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void non_positive_amount_is_rejected() {
        UUID a = TestSeed.wallet(jdbc, "a", 100);
        UUID b = TestSeed.wallet(jdbc, "b", 0);
        assertThatThrownBy(() -> TestSeed.transfer(jdbc, a, b, 0, "k", "f"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void self_transfer_is_rejected() {
        UUID a = TestSeed.wallet(jdbc, "a", 100);
        assertThatThrownBy(() -> TestSeed.transfer(jdbc, a, a, 10, "k", "f"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void transfer_to_ghost_wallet_is_rejected() {
        UUID a = TestSeed.wallet(jdbc, "a", 100);
        assertThatThrownBy(() -> TestSeed.transfer(jdbc, a, UUID.randomUUID(), 10, "k", "f"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void declined_without_a_reason_is_rejected() {
        UUID a = TestSeed.wallet(jdbc, "a", 100);
        UUID b = TestSeed.wallet(jdbc, "b", 0);
        assertThatThrownBy(() -> TestSeed.transfer(jdbc, a, b, 10, "k", "f",
                com.paytm.wallet.domain.Transfer.Status.DECLINED, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
