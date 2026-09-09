package com.paytm.wallet.repo;

import com.paytm.wallet.domain.Transfer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** JDBC access for {@code transfers}. The idempotency key lives here as a UNIQUE column. */
@Repository
public class TransferRepository {

    private final JdbcTemplate jdbc;

    public TransferRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Transfer> findByIdempotencyKey(String key) {
        throw new UnsupportedOperationException("scaffold");
    }

    public Optional<Transfer> findById(UUID id) {
        throw new UnsupportedOperationException("scaffold");
    }
}
