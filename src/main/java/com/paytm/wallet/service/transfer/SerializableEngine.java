package com.paytm.wallet.service.transfer;

import com.paytm.wallet.domain.Transfer;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Candidate C — SERIALIZABLE isolation.
 *
 * <p>Run the read-modify-write at {@code ISOLATION SERIALIZABLE} and retry on
 * SQLState {@code 40001} (serialization failure) with bounded attempts + jitter.
 * Postgres detects the write-skew / lost-update and aborts one txn.
 *
 * <p>Rejected as primary: correct, but pushes the reasoning surface to the whole
 * transaction's read/write set and forces a retry loop. Heavier to defend
 * line-by-line. Kept for benchmarking.
 *
 * TODO(scaffold): implement.
 */
public class SerializableEngine implements TransferEngine {

    private final JdbcTemplate jdbc;

    public SerializableEngine(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Transfer execute(TransferRequest request, String correlationId) {
        throw new UnsupportedOperationException("scaffold: SerializableEngine not implemented");
    }
}
