package com.paytm.wallet.service.transfer;

import com.paytm.wallet.domain.Transfer;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Candidate B — explicit pessimistic locking.
 *
 * <p>{@code SELECT ... FOR UPDATE} both wallet rows ordered by ascending id
 * (deterministic order → no deadlock), check the source balance in app code,
 * then apply both UPDATEs and insert the transfer + idempotency key in the
 * same transaction.
 *
 * <p>Rejected as primary because it is two round trips and a wider
 * check-then-act than Candidate A. Kept for benchmarking.
 *
 * TODO(scaffold): implement.
 */
public class SelectForUpdateEngine implements TransferEngine {

    private final JdbcTemplate jdbc;

    public SelectForUpdateEngine(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public TransferOutcome execute(TransferRequest request, String correlationId) {
        throw new UnsupportedOperationException("scaffold: SelectForUpdateEngine not implemented");
    }
}
