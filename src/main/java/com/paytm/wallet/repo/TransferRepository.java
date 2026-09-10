package com.paytm.wallet.repo;

import com.paytm.wallet.domain.Transfer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** JDBC access for {@code transfers}. The idempotency key lives here as a UNIQUE column. */
@Repository
public class TransferRepository {

    private static final String COLUMNS = RowMappers.TRANSFER_COLUMNS;

    private final JdbcTemplate jdbc;

    public TransferRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Transfer> findByIdempotencyKey(String key) {
        return first(jdbc.query(
                "SELECT " + COLUMNS + " FROM transfers WHERE idempotency_key = ?",
                RowMappers.TRANSFER, key));
    }

    public Optional<Transfer> findById(UUID id) {
        return first(jdbc.query(
                "SELECT " + COLUMNS + " FROM transfers WHERE id = ?",
                RowMappers.TRANSFER, id));
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
