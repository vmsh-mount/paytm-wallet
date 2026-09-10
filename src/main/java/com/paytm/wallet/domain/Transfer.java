package com.paytm.wallet.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A peer-to-peer transfer between two wallets.
 *
 * @param requestFingerprint lowercase hex SHA-256 of {@code from|to|amount_paise};
 *                           used to detect same-key / different-body replays (409)
 */
public record Transfer(
        UUID id,
        UUID fromWalletId,
        UUID toWalletId,
        long amountPaise,
        String idempotencyKey,
        String requestFingerprint,
        Status status,
        String declineReason,
        Instant createdAt) {

    public enum Status {
        CREATED,
        COMPLETED,
        DECLINED
    }
}
