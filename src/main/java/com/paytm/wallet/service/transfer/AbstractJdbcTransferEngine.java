package com.paytm.wallet.service.transfer;

import com.paytm.wallet.domain.Transfer;
import com.paytm.wallet.idempotency.RequestFingerprint;
import com.paytm.wallet.observability.WalletMetrics;
import com.paytm.wallet.repo.RowMappers;
import com.paytm.wallet.service.DomainExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Everything the three engines share: the transaction wrapper, idempotency
 * (pre-check, locked re-check, {@code UNIQUE} fallback, fingerprint/409), the
 * {@code transfers}-row insert, and the domain events + counters. Subclasses
 * implement only {@link #moveMoney(TransferRequest)} — the part that actually
 * differs (conditional {@code UPDATE} vs {@code SELECT FOR UPDATE} vs
 * {@code SERIALIZABLE} read-modify-write).
 */
public abstract class AbstractJdbcTransferEngine implements TransferEngine {

    private static final Logger log = LoggerFactory.getLogger(AbstractJdbcTransferEngine.class);

    protected final JdbcTemplate jdbc;
    protected final WalletMetrics metrics;
    protected final TransactionTemplate tx;

    protected AbstractJdbcTransferEngine(JdbcTemplate jdbc, PlatformTransactionManager txManager,
                                         WalletMetrics metrics, int isolationLevel) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setIsolationLevel(isolationLevel);
    }

    @Override
    public TransferOutcome execute(TransferRequest request, String correlationId) {
        try {
            return attempt(request);
        } catch (DuplicateKeyException raced) {
            // A concurrent first-timer with the same key won the INSERT and committed;
            // our tx rolled back. Read their row and return it (or 409 on a body mismatch).
            return tx.execute(status -> replayOrConflict(mustFind(request.idempotencyKey()), request));
        }
    }

    /** One transactional attempt: idempotency pre-check, then the engine-specific money move. */
    protected final TransferOutcome attempt(TransferRequest r) {
        return tx.execute(status -> {
            Transfer existing = findByKey(r.idempotencyKey());
            if (existing != null) {
                return replayOrConflict(existing, r); // pure replay — no lock taken
            }
            return moveMoney(r);
        });
    }

    /**
     * Move {@code amount} from {@code from} to {@code to} with no overdraft and no
     * lost update, then call {@link #completed(TransferRequest)} /
     * {@link #declined(TransferRequest)}. Runs inside the transaction opened by
     * {@link #attempt}.
     */
    protected abstract TransferOutcome moveMoney(TransferRequest r);

    // ---- helpers for subclasses -------------------------------------------

    /** Re-read the key once a lock is held; {@code null} if still absent. */
    protected TransferOutcome recheckKeyUnderLock(TransferRequest r) {
        Transfer existing = findByKey(r.idempotencyKey());
        return existing == null ? null : replayOrConflict(existing, r);
    }

    /** {@code SELECT ... FOR UPDATE} both wallet rows, ascending id — the deadlock-free lock order. */
    protected void lockBothWalletsInIdOrder(UUID a, UUID b) {
        List<UUID> ordered = Stream.of(a, b).sorted(Comparator.naturalOrder()).toList();
        jdbc.query("SELECT id FROM wallets WHERE id IN (?, ?) ORDER BY id FOR UPDATE",
                rs -> { /* rows discarded; we only need the locks */ },
                ordered.get(0), ordered.get(1));
    }

    protected long balanceOf(UUID walletId) {
        return jdbc.queryForObject("SELECT balance_paise FROM wallets WHERE id = ?", Long.class, walletId);
    }

    protected TransferOutcome completed(TransferRequest r) {
        log.atInfo().addKeyValue("event", "transfer.debited")
                .addKeyValue("wallet_id", r.fromWalletId()).addKeyValue("amount_paise", r.amountPaise())
                .log("wallet debited");
        log.atInfo().addKeyValue("event", "transfer.credited")
                .addKeyValue("wallet_id", r.toWalletId()).addKeyValue("amount_paise", r.amountPaise())
                .log("wallet credited");
        Transfer t = insertTransfer(r, Transfer.Status.COMPLETED, null);
        metrics.transferCreated();
        return TransferOutcome.fresh(t);
    }

    protected TransferOutcome declined(TransferRequest r) {
        log.atInfo().addKeyValue("event", "transfer.declined")
                .addKeyValue("from_wallet_id", r.fromWalletId()).addKeyValue("to_wallet_id", r.toWalletId())
                .addKeyValue("amount_paise", r.amountPaise()).addKeyValue("reason", "insufficient_funds")
                .log("transfer declined");
        Transfer t = insertTransfer(r, Transfer.Status.DECLINED, "insufficient_funds");
        metrics.declinedInsufficientFunds();
        return TransferOutcome.fresh(t);
    }

    // ---- idempotency internals -------------------------------------------

    private TransferOutcome replayOrConflict(Transfer existing, TransferRequest r) {
        String fingerprint = RequestFingerprint.of(r.fromWalletId(), r.toWalletId(), r.amountPaise());
        if (!fingerprint.equals(existing.requestFingerprint())) {
            log.atWarn().addKeyValue("event", "transfer.idempotency_conflict")
                    .addKeyValue("idempotency_key", r.idempotencyKey())
                    .addKeyValue("transfer_id", existing.id())
                    .log("idempotency key reused with a different body");
            throw new DomainExceptions.IdempotencyConflict(
                    "idempotency_key already used for a different transfer");
        }
        log.atInfo().addKeyValue("event", "transfer.idempotent_replay")
                .addKeyValue("idempotency_key", r.idempotencyKey())
                .addKeyValue("transfer_id", existing.id())
                .addKeyValue("status", existing.status().name())
                .log("idempotent replay");
        metrics.idempotentReplay();
        return TransferOutcome.replay(existing);
    }

    private Transfer findByKey(String idempotencyKey) {
        List<Transfer> rows = jdbc.query(
                "SELECT " + RowMappers.TRANSFER_COLUMNS + " FROM transfers WHERE idempotency_key = ?",
                RowMappers.TRANSFER, idempotencyKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Transfer mustFind(String idempotencyKey) {
        Transfer t = findByKey(idempotencyKey);
        if (t == null) {
            throw new IllegalStateException(
                    "idempotency_key " + idempotencyKey + " vanished after a unique-violation");
        }
        return t;
    }

    private static final String INSERT_RETURNING =
            "INSERT INTO transfers "
            + "(from_wallet_id, to_wallet_id, amount_paise, idempotency_key, "
            + " request_fingerprint, status, decline_reason) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?) "
            + "RETURNING " + RowMappers.TRANSFER_COLUMNS;

    private Transfer insertTransfer(TransferRequest r, Transfer.Status status, String declineReason) {
        String fingerprint = RequestFingerprint.of(r.fromWalletId(), r.toWalletId(), r.amountPaise());
        return jdbc.queryForObject(INSERT_RETURNING, RowMappers.TRANSFER,
                r.fromWalletId(), r.toWalletId(), r.amountPaise(), r.idempotencyKey(),
                fingerprint, status.name(), declineReason);
    }
}
