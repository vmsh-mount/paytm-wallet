package com.paytm.wallet.repo;

import com.paytm.wallet.domain.Wallet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** JDBC access for {@code wallets}. Raw SQL on purpose — no JPA. */
@Repository
public class WalletRepository {

    private final JdbcTemplate jdbc;

    public WalletRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** INSERT ... ON CONFLICT (user_id) DO NOTHING. Returns true if a row was created. */
    public boolean insertIfAbsent(String userId) {
        throw new UnsupportedOperationException("scaffold");
    }

    public Optional<Wallet> findByUserId(String userId) {
        throw new UnsupportedOperationException("scaffold");
    }

    public Optional<Wallet> findById(UUID id) {
        throw new UnsupportedOperationException("scaffold");
    }
}
