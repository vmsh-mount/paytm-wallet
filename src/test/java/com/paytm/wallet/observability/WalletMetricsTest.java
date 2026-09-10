package com.paytm.wallet.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class WalletMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final WalletMetrics metrics = new WalletMetrics(registry);

    private double count(String name) {
        return registry.get(name).counter().count();
    }

    @Test
    void each_counter_moves_only_on_its_own_event() {
        metrics.transferCompleted();
        metrics.transferCompleted();
        metrics.declinedInsufficientFunds();
        metrics.idempotentReplay();
        metrics.conflict();

        assertThat(count("wallet.transfers.completed")).isEqualTo(2.0);
        assertThat(count("wallet.transfers.declined")).isEqualTo(1.0);
        assertThat(count("wallet.transfers.idempotent_replay")).isEqualTo(1.0);
        assertThat(count("wallet.transfers.conflict")).isEqualTo(1.0);
    }

    @Test
    void declined_counter_is_tagged_with_the_reason() {
        metrics.declinedInsufficientFunds();
        assertThat(registry.get("wallet.transfers.declined")
                .tag("reason", "insufficient_funds").counter().count()).isEqualTo(1.0);
    }

    @Test
    void serialization_retries_are_tagged_by_engine() {
        metrics.serializationRetry("serializable");
        metrics.serializationRetry("serializable");
        assertThat(registry.get("wallet.transfer.retries")
                .tag("engine", "serializable").counter().count()).isEqualTo(2.0);
    }

    @Test
    void transfer_observed_records_a_tagged_timer_and_amount_only_on_completed() {
        metrics.transferObserved("conditional-update", "completed", 5_000, TimeUnit.MILLISECONDS.toNanos(3));
        metrics.transferObserved("conditional-update", "declined", 9_000, TimeUnit.MILLISECONDS.toNanos(1));

        assertThat(registry.get("wallet.transfer.duration")
                .tag("engine", "conditional-update").tag("outcome", "completed").timer().count()).isEqualTo(1L);
        assertThat(registry.get("wallet.transfer.duration")
                .tag("outcome", "declined").timer().count()).isEqualTo(1L);
        assertThat(registry.get("wallet.transfer.amount").summary().count())
                .as("amount recorded for completed only").isEqualTo(1L);
        assertThat(registry.get("wallet.transfer.amount").summary().totalAmount()).isEqualTo(5_000.0);
    }
}
