package com.paytm.wallet;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for the four graded invariants. Run against a real Postgres
 * via Testcontainers. Each test should be parameterised over the three
 * {@code TransferEngine} implementations.
 *
 * TODO(scaffold): implement.
 */
@Testcontainers
@SpringBootTest
@Disabled("scaffold")
class InvariantsIT {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void conservation_holds_under_concurrent_transfers() {}

    @Test
    void no_overdraft_under_contention() {}

    @Test
    void same_idempotency_key_applies_once() {}

    @Test
    void same_key_different_body_is_409() {}

    @Test
    void concurrent_get_or_create_yields_one_wallet() {}
}
