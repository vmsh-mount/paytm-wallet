package com.paytm.wallet.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * The one place money-relevant events are written to the structured log. Every
 * call emits a single JSON line carrying {@code event=<code>} plus the fields for
 * that event and (from the MDC) {@code correlation_id} / {@code user_id}.
 *
 * <p>Rules: never log a bearer token; never log a full {@code idempotency_key}
 * (it is client-controlled and may repeat across users) — log
 * {@link #keyHash(String)}, a 12-hex-char SHA-256 prefix, enough to correlate a
 * retry storm without leaking the value.
 */
public final class DomainEvents {

    private static final Logger log = LoggerFactory.getLogger("com.paytm.wallet.events");

    public static void walletCreated(UUID walletId, String userId) {
        event(DomainEvent.WALLET_CREATED)
                .addKeyValue("wallet_id", walletId)
                .addKeyValue("user_id", userId)
                .log("wallet created");
    }

    public static void transferReceived(UUID from, UUID to, long amountPaise, String idempotencyKey) {
        event(DomainEvent.TRANSFER_RECEIVED)
                .addKeyValue("from", from)
                .addKeyValue("to", to)
                .addKeyValue("amount_paise", amountPaise)
                .addKeyValue("idempotency_key_hash", keyHash(idempotencyKey))
                .log("transfer received");
    }

    public static void transferDebited(UUID transferId, UUID from, long amountPaise, long fromBalanceAfter) {
        event(DomainEvent.TRANSFER_DEBITED)
                .addKeyValue("transfer_id", transferId)
                .addKeyValue("from", from)
                .addKeyValue("amount_paise", amountPaise)
                .addKeyValue("from_balance_after", fromBalanceAfter)
                .log("wallet debited");
    }

    public static void transferCredited(UUID transferId, UUID to, long amountPaise, long toBalanceAfter) {
        event(DomainEvent.TRANSFER_CREDITED)
                .addKeyValue("transfer_id", transferId)
                .addKeyValue("to", to)
                .addKeyValue("amount_paise", amountPaise)
                .addKeyValue("to_balance_after", toBalanceAfter)
                .log("wallet credited");
    }

    public static void transferCompleted(UUID transferId, long amountPaise, double latencyMs) {
        event(DomainEvent.TRANSFER_COMPLETED)
                .addKeyValue("transfer_id", transferId)
                .addKeyValue("amount_paise", amountPaise)
                .addKeyValue("latency_ms", Math.round(latencyMs * 1000) / 1000.0) // sub-ms precision
                .log("transfer completed");
    }

    public static void transferDeclined(UUID transferId, UUID from, long amountPaise, String reason) {
        event(DomainEvent.TRANSFER_DECLINED)
                .addKeyValue("transfer_id", transferId)
                .addKeyValue("from", from)
                .addKeyValue("amount_paise", amountPaise)
                .addKeyValue("reason", reason)
                .log("transfer declined");
    }

    public static void idempotentReplay(UUID transferId, String idempotencyKey) {
        event(DomainEvent.TRANSFER_IDEMPOTENT_REPLAY)
                .addKeyValue("transfer_id", transferId)
                .addKeyValue("idempotency_key_hash", keyHash(idempotencyKey))
                .log("idempotent replay");
    }

    public static void conflict(String idempotencyKey) {
        log.atWarn()
                .addKeyValue("event", DomainEvent.TRANSFER_CONFLICT.code())
                .addKeyValue("idempotency_key_hash", keyHash(idempotencyKey))
                .log("idempotency key reused with a different body");
    }

    public static void serializationRetry(String idempotencyKey, int attempt) {
        event(DomainEvent.SERIALIZATION_RETRY)
                .addKeyValue("idempotency_key_hash", keyHash(idempotencyKey))
                .addKeyValue("attempt", attempt)
                .log("retrying after 40001");
    }

    public static void serializationExhausted(String idempotencyKey, int attempts) {
        log.atWarn()
                .addKeyValue("event", DomainEvent.SERIALIZATION_EXHAUSTED.code())
                .addKeyValue("idempotency_key_hash", keyHash(idempotencyKey))
                .addKeyValue("attempts", attempts)
                .log("serialization conflict unresolved after retry budget");
    }

    /** 12 lowercase hex chars of SHA-256(key). Stable, non-reversible, enough to group a retry storm. */
    public static String keyHash(String idempotencyKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(idempotencyKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static LoggingEventBuilder event(DomainEvent e) {
        return log.atInfo().addKeyValue("event", e.code());
    }

    private DomainEvents() {}
}
