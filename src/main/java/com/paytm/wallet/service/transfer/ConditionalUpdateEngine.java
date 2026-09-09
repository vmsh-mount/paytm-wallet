package com.paytm.wallet.service.transfer;

import com.paytm.wallet.domain.Transfer;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Candidate A — simplest-correct.
 *
 * <p>Single atomic statement does the overdraft check and the debit together:
 * <pre>
 *   UPDATE wallets SET balance_paise = balance_paise - :amt
 *   WHERE id = :from AND balance_paise >= :amt
 * </pre>
 * If {@code rowsAffected == 0} the transfer is DECLINED (insufficient funds) —
 * no partial apply is possible. The credit is a second UPDATE. Both wallet rows
 * are touched in ascending-id order to prevent A→B / B→A deadlock. The transfer
 * row and the idempotency key are inserted in the same transaction.
 *
 * TODO(scaffold): implement.
 */
public class ConditionalUpdateEngine implements TransferEngine {

    private final JdbcTemplate jdbc;

    public ConditionalUpdateEngine(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Transfer execute(TransferRequest request, String correlationId) {
        throw new UnsupportedOperationException("scaffold: ConditionalUpdateEngine not implemented");
    }
}
