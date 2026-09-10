package com.paytm.wallet.service.transfer;

import com.paytm.wallet.observability.WalletMetrics;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/**
 * Candidate A — simplest-correct. In one {@code READ COMMITTED} transaction:
 * lock both wallet rows {@code FOR UPDATE} in id order, re-check the key, then a
 * single conditional statement does the overdraft check and the debit together
 * ({@code UPDATE ... SET balance = balance - :amt WHERE id = :from AND balance >= :amt};
 * {@code rowsAffected == 0} ⇒ DECLINED, no partial apply), then credit.
 *
 * <p>The shortest critical section of the three: one statement for check+debit.
 */
public class ConditionalUpdateEngine extends AbstractJdbcTransferEngine {

    public ConditionalUpdateEngine(JdbcTemplate jdbc, PlatformTransactionManager txManager,
                                   WalletMetrics metrics) {
        super(jdbc, txManager, metrics, TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    protected TransferOutcome moveMoney(TransferRequest r) {
        lockBothWalletsInIdOrder(r.fromWalletId(), r.toWalletId());
        TransferOutcome replay = recheckKeyUnderLock(r);
        if (replay != null) {
            return replay;
        }

        long amount = r.amountPaise();
        int debited = jdbc.update(
                "UPDATE wallets SET balance_paise = balance_paise - ? WHERE id = ? AND balance_paise >= ?",
                amount, r.fromWalletId(), amount);
        if (debited == 0) {
            return declined(r);
        }
        jdbc.update("UPDATE wallets SET balance_paise = balance_paise + ? WHERE id = ?",
                amount, r.toWalletId());
        return completed(r);
    }
}
