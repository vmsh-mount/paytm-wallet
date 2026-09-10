package com.paytm.wallet.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Stable hash of the money-affecting fields of a transfer request: lowercase hex
 * SHA-256 of {@code "from|to|amount_paise"}. Stored on the transfer row so that
 * on an idempotency-key replay a mismatch (same key, different body) can be
 * detected without trusting the client — the 409 path (TASK-05).
 */
public final class RequestFingerprint {

    public static String of(UUID from, UUID to, long amountPaise) {
        String canonical = from + "|" + to + "|" + amountPaise;
        return HexFormat.of().formatHex(sha256(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e); // unreachable on any JRE
        }
    }

    private RequestFingerprint() {}
}
