package com.paytm.wallet;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Smoke test: the full Spring context starts against a real Postgres (so Flyway
 * migrations, the datasource pool, and every bean wire up). This is the only
 * test TASK-00 ships — domain tests arrive with their tasks.
 */
@SpringBootTest
@Testcontainers
class WalletApplicationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void contextLoads() {
        // pass iff the context above bootstrapped
    }
}
