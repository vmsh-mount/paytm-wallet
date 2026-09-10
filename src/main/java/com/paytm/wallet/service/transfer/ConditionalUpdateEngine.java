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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Candidate A — simplest-correct. One {@code READ COMMITTED} transaction:
 * <ol>
 *   <li>short-circuit if this {@code idempotency_key} is already recorded
 *       (same fingerprint ⇒ return the stored transfer; different ⇒ 409);</li>
 *   <li>lock both wallet rows {@code FOR UPDATE} in ascending id order —
 *       deterministic order ⇒ A→B and B→A can't deadlock — then re-check the key
 *       under the lock;</li>
 *   <li>debit with a single conditional statement
 *       {@code UPDATE ... SET balance = balance - :amt WHERE id = :from AND balance >= :amt}
 *       — {@code rowsAffected == 0} ⇒ DECLINED (insufficient_funds), no partial apply;</li>
 *   <li>credit the destination;</li>
 *   <li>insert the {@code transfers} row (key + fingerprint) <b>in the same tx</b>.</li>
 * </ol>
 * Exactly-once: the {@code UNIQUE(idempotency_key)} row commits with the balance
 * updates, so a key exists iff the money moved. Conservation: {@code -amt} /
 * {@code +amt} of the same integer, committed together, no other writer.
 */
public class ConditionalUpdateEngine implements TransferEngine {

    private static final Logger log = LoggerFactory.getLogger(ConditionalUpdateEngine.class);

    private final JdbcTemplate jdbc;
    private final WalletMetrics metrics;
    private final TransactionTemplate tx;

    public ConditionalUpdateEngine(JdbcTemplate jdbc, PlatformTransactionManager txManager,
                                   WalletMetrics metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public TransferOutcome execute(TransferRequest request, String correlationId) {
        try {
            return tx.execute(status -> move(request));
        } catch (DuplicateKeyException raced) {
            // A concurrent first-timer with the same key won the INSERT and committed;
            // our balance changes rolled back. Read their row and return it (or 409).
            return tx.execute(status -> replayOrConflict(mustFind(request.idempotencyKey()), request));
        }
    }

    private TransferOutcome move(TransferRequest r) {
        Transfer existing = findByKey(r.idempotencyKey());
        if (existing != null) {
            return replayOrConflict(existing, r); // pure replay — no wallet lock taken
        }

        lockBothWalletsInIdOrder(r.fromWalletId(), r.toWalletId());

        existing = findByKey(r.idempotencyKey()); // re-check, now serialized by the row lock
        if (existing != null) {
            return replayOrConflict(existing, r);
        }

        long amount = r.amountPaise();
        int debited = jdbc.update(
                "UPDATE wallets SET balance_paise = balance_paise - ? WHERE id = ? AND balance_paise >= ?",
                amount, r.fromWalletId(), amount);
        if (debited == 0) {
            log.atInfo().addKeyValue("event", "transfer.declined")
                    .addKeyValue("from_wallet_id", r.fromWalletId()).addKeyValue("to_wallet_id", r.toWalletId())
                    .addKeyValue("amount_paise", amount).addKeyValue("reason", "insufficient_funds")
                    .log("transfer declined");
            Transfer declined = insertTransfer(r, Transfer.Status.DECLINED, "insufficient_funds");
            metrics.declinedInsufficientFunds();
            return TransferOutcome.fresh(declined);
        }
        log.atInfo().addKeyValue("event", "transfer.debited")
                .addKeyValue("wallet_id", r.fromWalletId()).addKeyValue("amount_paise", amount).log("wallet debited");

        jdbc.update("UPDATE wallets SET balance_paise = balance_paise + ? WHERE id = ?", amount, r.toWalletId());
        log.atInfo().addKeyValue("event", "transfer.credited")
                .addKeyValue("wallet_id", r.toWalletId()).addKeyValue("amount_paise", amount).log("wallet credited");

        Transfer completed = insertTransfer(r, Transfer.Status.COMPLETED, null);
        metrics.transferCreated();
        return TransferOutcome.fresh(completed);
    }

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

    /** {@code SELECT ... FOR UPDATE} both rows sorted by id — the deadlock-free lock order. */
    private void lockBothWalletsInIdOrder(UUID a, UUID b) {
        List<UUID> ordered = java.util.stream.Stream.of(a, b).sorted(Comparator.naturalOrder()).toList();
        jdbc.query("SELECT id FROM wallets WHERE id IN (?, ?) ORDER BY id FOR UPDATE",
                rs -> { /* rows discarded; we only need the locks */ },
                ordered.get(0), ordered.get(1));
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
