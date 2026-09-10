package com.paytm.wallet.service.transfer;

import com.paytm.wallet.domain.Transfer;
import com.paytm.wallet.idempotency.RequestFingerprint;
import com.paytm.wallet.repo.RowMappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *   <li>lock both wallet rows {@code FOR UPDATE} in ascending id order —
 *       deterministic order ⇒ A→B and B→A can't deadlock;</li>
 *   <li>debit with a single conditional statement
 *       {@code UPDATE ... SET balance = balance - :amt WHERE id = :from AND balance >= :amt}
 *       — {@code rowsAffected == 0} ⇒ DECLINED (insufficient_funds), no partial apply;</li>
 *   <li>credit the destination;</li>
 *   <li>insert the {@code transfers} row (COMPLETED or DECLINED) in the same tx.</li>
 * </ol>
 * Conservation: debit and credit are {@code -amt} / {@code +amt} of the same
 * integer, committed together; {@code wallets.balance_paise} has no other writer.
 */
public class ConditionalUpdateEngine implements TransferEngine {

    private static final Logger log = LoggerFactory.getLogger(ConditionalUpdateEngine.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ConditionalUpdateEngine(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public Transfer execute(TransferRequest request, String correlationId) {
        return tx.execute(status -> move(request));
    }

    private Transfer move(TransferRequest r) {
        long amount = r.amountPaise();
        UUID from = r.fromWalletId();
        UUID to = r.toWalletId();

        lockBothWalletsInIdOrder(from, to);

        int debited = jdbc.update(
                "UPDATE wallets SET balance_paise = balance_paise - ? WHERE id = ? AND balance_paise >= ?",
                amount, from, amount);
        if (debited == 0) {
            log.atInfo().addKeyValue("event", "transfer.declined")
                    .addKeyValue("from_wallet_id", from).addKeyValue("to_wallet_id", to)
                    .addKeyValue("amount_paise", amount).addKeyValue("reason", "insufficient_funds")
                    .log("transfer declined");
            return insertTransfer(r, Transfer.Status.DECLINED, "insufficient_funds");
        }
        log.atInfo().addKeyValue("event", "transfer.debited")
                .addKeyValue("wallet_id", from).addKeyValue("amount_paise", amount).log("wallet debited");

        jdbc.update("UPDATE wallets SET balance_paise = balance_paise + ? WHERE id = ?", amount, to);
        log.atInfo().addKeyValue("event", "transfer.credited")
                .addKeyValue("wallet_id", to).addKeyValue("amount_paise", amount).log("wallet credited");

        return insertTransfer(r, Transfer.Status.COMPLETED, null);
    }

    /** {@code SELECT ... FOR UPDATE} both rows sorted by id — the deadlock-free lock order. */
    private void lockBothWalletsInIdOrder(UUID a, UUID b) {
        List<UUID> ordered = java.util.stream.Stream.of(a, b).sorted(Comparator.naturalOrder()).toList();
        jdbc.query("SELECT id FROM wallets WHERE id IN (?, ?) ORDER BY id FOR UPDATE",
                rs -> { /* rows discarded; we only need the locks */ },
                ordered.get(0), ordered.get(1));
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
