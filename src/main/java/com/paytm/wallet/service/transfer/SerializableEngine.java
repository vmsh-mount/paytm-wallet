package com.paytm.wallet.service.transfer;

import com.paytm.wallet.observability.WalletMetrics;
import com.paytm.wallet.service.DomainExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Candidate C — {@code SERIALIZABLE} isolation, plain read-modify-write, no
 * explicit locks. Postgres SSI detects the lost update / write-skew and aborts
 * one transaction with SQLSTATE {@code 40001}; we retry the whole thing with
 * bounded attempts + exponential backoff + jitter. Exhaustion → {@code 503}.
 *
 * <p>Rejected as primary: correct, but the reasoning surface is the whole
 * transaction's read/write set, and a rising conflict rate turns into retry cost
 * (measured in {@code bench/RESULTS.md}). The blind {@code UPDATE}s are still
 * applied in ascending wallet-id order so two reverse transfers can't deadlock.
 */
public class SerializableEngine extends AbstractJdbcTransferEngine {

    private static final Logger log = LoggerFactory.getLogger(SerializableEngine.class);
    private static final String ENGINE = "serializable";

    private final int maxRetries;

    public SerializableEngine(JdbcTemplate jdbc, PlatformTransactionManager txManager,
                              WalletMetrics metrics, int maxRetries) {
        super(jdbc, txManager, metrics, TransactionDefinition.ISOLATION_SERIALIZABLE);
        this.maxRetries = maxRetries;
    }

    @Override
    public TransferOutcome execute(TransferRequest request, String correlationId) {
        int attempt = 0;
        while (true) {
            try {
                return super.execute(request, correlationId);
            } catch (DataAccessException dae) {
                if (!isSerializationFailure(dae)) {
                    throw dae;
                }
                if (++attempt > maxRetries) {
                    log.atWarn().addKeyValue("event", "transfer.serialization_failure_exhausted")
                            .addKeyValue("idempotency_key", request.idempotencyKey())
                            .addKeyValue("attempts", attempt)
                            .log("serialization conflict unresolved after retry budget");
                    throw new DomainExceptions.SerializationExhausted(
                            "serialization conflict not resolved after " + maxRetries + " retries");
                }
                metrics.serializationRetry(ENGINE);
                log.atDebug().addKeyValue("event", "transfer.serialization_retry")
                        .addKeyValue("idempotency_key", request.idempotencyKey())
                        .addKeyValue("attempt", attempt).log("retrying after 40001");
                backoff(attempt);
            }
        }
    }

    /** True for SQLSTATE 40001 (serialization failure) or 40P01 (deadlock) anywhere in the chain. */
    private static boolean isSerializationFailure(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException se) {
                String state = se.getSQLState();
                if ("40001".equals(state) || "40P01".equals(state)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    protected TransferOutcome moveMoney(TransferRequest r) {
        long amount = r.amountPaise();
        long fromBalance = balanceOf(r.fromWalletId());
        if (fromBalance < amount) {
            return declined(r);
        }
        long toBalance = balanceOf(r.toWalletId());

        // Apply both blind writes lower-id-first: no FOR UPDATE, but still a fixed
        // order so a reverse transfer can't produce an ABBA lock cycle.
        if (r.fromWalletId().compareTo(r.toWalletId()) < 0) {
            setBalance(r.fromWalletId(), fromBalance - amount);
            setBalance(r.toWalletId(), toBalance + amount);
        } else {
            setBalance(r.toWalletId(), toBalance + amount);
            setBalance(r.fromWalletId(), fromBalance - amount);
        }
        return completed(r);
    }

    private void setBalance(UUID walletId, long value) {
        jdbc.update("UPDATE wallets SET balance_paise = ? WHERE id = ?", value, walletId);
    }

    private static void backoff(int attempt) {
        long base = Math.min(5L << Math.min(attempt, 6), 200L);
        long sleep = base + ThreadLocalRandom.current().nextLong(base + 1);
        try {
            Thread.sleep(sleep);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DomainExceptions.SerializationExhausted("interrupted while backing off");
        }
    }
}
