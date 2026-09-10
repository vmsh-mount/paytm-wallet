package com.paytm.wallet.repo;

import com.paytm.wallet.domain.Wallet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** JDBC access for {@code wallets}. Raw SQL on purpose — no JPA. */
@Repository
public class WalletRepository {

    private static final String COLUMNS = "id, user_id, balance_paise, created_at";

    private final JdbcTemplate jdbc;

    public WalletRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * {@code INSERT ... ON CONFLICT (user_id) DO NOTHING}. The {@code UNIQUE(user_id)}
     * index is the sole arbiter — the DB never creates a second row, and a concurrent
     * loser simply gets 0 rows affected.
     *
     * @return true iff this call created the row (false = it already existed)
     */
    public boolean insertIfAbsent(String userId) {
        return jdbc.update(
                "INSERT INTO wallets (user_id) VALUES (?) ON CONFLICT (user_id) DO NOTHING",
                userId) == 1;
    }

    public Optional<Wallet> findByUserId(String userId) {
        return first(jdbc.query(
                "SELECT " + COLUMNS + " FROM wallets WHERE user_id = ?",
                RowMappers.WALLET, userId));
    }

    public Optional<Wallet> findById(UUID id) {
        return first(jdbc.query(
                "SELECT " + COLUMNS + " FROM wallets WHERE id = ?",
                RowMappers.WALLET, id));
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
