package com.paytm.wallet.support;

import com.paytm.wallet.domain.Transfer;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * Test-only row inserts. NOT a migration and NOT a production code path — the
 * real write paths land in TASK-03 / TASK-04. Kept here so integration tests can
 * arrange state without hand-writing SQL in every class.
 */
public final class TestSeed {

    public static UUID wallet(JdbcTemplate jdbc, String userId, long balancePaise) {
        return jdbc.queryForObject(
                "INSERT INTO wallets (user_id, balance_paise) VALUES (?, ?) RETURNING id",
                UUID.class, userId, balancePaise);
    }

    /** A COMPLETED transfer with a null decline_reason. */
    public static UUID transfer(JdbcTemplate jdbc, UUID from, UUID to, long amountPaise,
                                String idempotencyKey, String fingerprint) {
        return transfer(jdbc, from, to, amountPaise, idempotencyKey, fingerprint,
                Transfer.Status.COMPLETED, null);
    }

    public static UUID transfer(JdbcTemplate jdbc, UUID from, UUID to, long amountPaise,
                                String idempotencyKey, String fingerprint,
                                Transfer.Status status, String declineReason) {
        return jdbc.queryForObject("""
                INSERT INTO transfers
                    (from_wallet_id, to_wallet_id, amount_paise, idempotency_key,
                     request_fingerprint, status, decline_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                UUID.class, from, to, amountPaise, idempotencyKey, fingerprint,
                status.name(), declineReason);
    }

    private TestSeed() {}
}
