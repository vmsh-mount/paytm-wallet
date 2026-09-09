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
 * Base for {@code *IT} classes: one real Postgres (Testcontainers) shared across
 * the suite via {@code @ServiceConnection}, Flyway runs on context start, and
 * every test begins with empty tables.
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
        jdbc.execute("TRUNCATE transfers, wallets RESTART IDENTITY CASCADE");
    }
}
