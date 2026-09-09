package com.paytm.wallet.idempotency;

import java.util.UUID;

/**
 * Stable hash of the money-affecting fields of a transfer request. Stored on the
 * transfer row; on an idempotency-key replay, a mismatch means same key /
 * different body -> 409.
 *
 * TODO(scaffold): implement (SHA-256 over from|to|amount, hex-encoded).
 */
public final class RequestFingerprint {

    public static String of(UUID from, UUID to, long amountPaise) {
        throw new UnsupportedOperationException("scaffold");
    }

    private RequestFingerprint() {}
}
