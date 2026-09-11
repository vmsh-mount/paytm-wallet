package com.paytm.wallet.service.transfer;

import com.paytm.wallet.observability.WalletMetrics;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/**
 * Candidate B — explicit pessimistic locking. {@code READ COMMITTED}; lock both
 * wallet rows {@code FOR UPDATE} in id order, check the source balance in the
 * application, then two plain {@code UPDATE}s.
 *
 * <p>Rejected as primary: an extra round trip (read the balance) and a wider
 * check-then-act window than Candidate A's single conditional statement — though
 * still correct because the row is already write-locked. Kept for benchmarking.
 */
public class SelectForUpdateEngine extends AbstractJdbcTransferEngine {

    public SelectForUpdateEngine(JdbcTemplate jdbc, PlatformTransactionManager txManager,
                                 WalletMetrics metrics) {
        super(jdbc, txManager, metrics, Engine.SELECT_FOR_UPDATE, TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    protected TransferOutcome moveMoney(TransferRequest r) {
        lockBothWalletsInIdOrder(r.fromWalletId(), r.toWalletId());
        TransferOutcome replay = recheckKeyUnderLock(r);
        if (replay != null) {
            return replay;
        }

        long amount = r.amountPaise();
        if (balanceOf(r.fromWalletId()) < amount) { // row is locked → this read is stable
            return declined(r);
        }
        long fromBalanceAfter = debit(r.fromWalletId(), amount);
        long toBalanceAfter = credit(r.toWalletId(), amount);
        return completed(r, fromBalanceAfter, toBalanceAfter);
    }
}
