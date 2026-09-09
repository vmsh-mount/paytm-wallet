package com.paytm.wallet.domain;

import java.time.Instant;
import java.util.UUID;

/** A peer-to-peer transfer between two wallets. */
public record Transfer(
        UUID id,
        UUID fromWalletId,
        UUID toWalletId,
        long amountPaise,
        String idempotencyKey,
        Status status,
        String declineReason,
        Instant createdAt) {

    public enum Status {
        CREATED,
        COMPLETED,
        DECLINED
    }
}
