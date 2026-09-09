package com.paytm.wallet.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for {@code *IT} classes: one real Postgres via Testcontainers, Flyway runs
 * on context start, every test begins with empty tables.
 *
 * <p>The container is a hand-managed singleton — started once in a static
 * initializer and never explicitly stopped (Ryuk / JVM exit reaps it). It is
 * shared across every IT class in the failsafe run, which avoids the per-class
 * container start/stop churn — and the races with Spring's context cache — that
 * a {@code @Testcontainers}-managed {@code static @Container} caused.
 */
@SpringBootTest
public abstract class AbstractPostgresIT {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void truncateAll() {
        jdbc.execute("TRUNCATE transfers, wallets CASCADE");
    }
}
