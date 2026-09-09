package com.paytm.wallet.repo;

import com.paytm.wallet.domain.Transfer;
import com.paytm.wallet.domain.Wallet;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Column -> record mappers. One definition per table so every query returns the
 * same shape. {@code timestamptz} is read as an instant and exposed as a UTC
 * {@link Instant}; a NULL {@code decline_reason} maps to {@code null}.
 */
public final class RowMappers {

    public static final RowMapper<Wallet> WALLET = (rs, rowNum) -> new Wallet(
            rs.getObject("id", UUID.class),
            rs.getString("user_id"),
            rs.getLong("balance_paise"),
            toInstant(rs.getTimestamp("created_at")));

    public static final RowMapper<Transfer> TRANSFER = (rs, rowNum) -> new Transfer(
            rs.getObject("id", UUID.class),
            rs.getObject("from_wallet_id", UUID.class),
            rs.getObject("to_wallet_id", UUID.class),
            rs.getLong("amount_paise"),
            rs.getString("idempotency_key"),
            rs.getString("request_fingerprint"),
            Transfer.Status.valueOf(rs.getString("status")),
            rs.getString("decline_reason"),
            toInstant(rs.getTimestamp("created_at")));

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private RowMappers() {}
}
