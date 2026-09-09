package com.paytm.wallet.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base for {@code *IT} classes: a real Postgres via Testcontainers wired in with
 * {@code @ServiceConnection}, Flyway runs on context start, and every test begins
 * with empty tables.
 *
 * <p>The {@code static @Container} has per-class lifecycle, so each IT class
 * spins up its own container (~a few seconds each on CI). Acceptable at this
 * count; if the IT suite grows, switch to a hand-managed singleton container
 * (started in a static initializer, never stopped).
 */
@SpringBootTest
@Testcontainers
public abstract class AbstractPostgresIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void truncateAll() {
        jdbc.execute("TRUNCATE transfers, wallets CASCADE");
    }
}
