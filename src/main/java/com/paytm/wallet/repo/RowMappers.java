package com.paytm.wallet.repo;

import com.paytm.wallet.domain.Transfer;
import com.paytm.wallet.domain.Wallet;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Column -> record mappers. One definition per table so every query returns the
 * same shape. {@code timestamptz} is read as an {@link OffsetDateTime} and
 * exposed as a UTC {@link Instant}; a NULL {@code decline_reason} maps to
 * {@code null}.
 */
public final class RowMappers {

    public static final RowMapper<Wallet> WALLET = (rs, rowNum) -> new Wallet(
            rs.getObject("id", UUID.class),
            rs.getString("user_id"),
            rs.getLong("balance_paise"),
            toInstant(rs.getObject("created_at", OffsetDateTime.class)));

    public static final RowMapper<Transfer> TRANSFER = (rs, rowNum) -> new Transfer(
            rs.getObject("id", UUID.class),
            rs.getObject("from_wallet_id", UUID.class),
            rs.getObject("to_wallet_id", UUID.class),
            rs.getLong("amount_paise"),
            rs.getString("idempotency_key"),
            rs.getString("request_fingerprint"),
            Transfer.Status.valueOf(rs.getString("status")),
            rs.getString("decline_reason"),
            toInstant(rs.getObject("created_at", OffsetDateTime.class)));

    private static Instant toInstant(OffsetDateTime odt) {
        return odt == null ? null : odt.toInstant();
    }

    private RowMappers() {}
}
