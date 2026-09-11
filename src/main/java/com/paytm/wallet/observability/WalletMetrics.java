package com.paytm.wallet.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Domain meters, exposed at {@code /metrics} (and {@code /actuator/prometheus}).
 * Request rate, latency p99 and error rate come from Spring Boot's
 * {@code http.server.requests} timer; these are the money-domain additions.
 *
 * <p>Deliberately low-cardinality — no {@code user_id} / {@code wallet_id} /
 * {@code idempotency_key} tags. The transfer timer is tagged {@code {engine,outcome}}
 * so the dashboard doubles as the engine-comparison view when {@code TRANSFER_ENGINE}
 * is flipped.
 */
@Component
public class WalletMetrics {

    private final MeterRegistry registry;
    private final Counter transfersCompleted;
    private final Counter transfersDeclinedInsufficientFunds;
    private final Counter idempotentReplays;
    private final Counter conflicts;
    private final DistributionSummary transferAmount;

    public WalletMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.transfersCompleted = Counter.builder("wallet.transfers.completed")
                .description("Transfers that moved money").register(registry);
        this.transfersDeclinedInsufficientFunds = Counter.builder("wallet.transfers.declined")
                .description("Transfers declined for insufficient funds")
                .tag("reason", "insufficient_funds").register(registry);
        this.idempotentReplays = Counter.builder("wallet.transfers.idempotent_replay")
                .description("Repeat requests that returned a stored transfer").register(registry);
        this.conflicts = Counter.builder("wallet.transfers.conflict")
                .description("Idempotency key reused with a different body (409)").register(registry);
        this.transferAmount = DistributionSummary.builder("wallet.transfer.amount")
                .description("Distribution of completed transfer sizes")
                .baseUnit("paise").publishPercentiles(0.5, 0.95, 0.99).register(registry);
    }

    public void transferCompleted() { transfersCompleted.increment(); }
    public void declinedInsufficientFunds() { transfersDeclinedInsufficientFunds.increment(); }
    public void idempotentReplay() { idempotentReplays.increment(); }
    public void conflict() { conflicts.increment(); }

    /** A serializable-isolation transaction was retried after a {@code 40001} conflict. */
    public void serializationRetry(String engine) {
        Counter.builder("wallet.transfer.retries")
                .description("Serializable-isolation retries after 40001")
                .tag("engine", engine).register(registry).increment();
    }

    /**
     * One {@code POST /transfers} call, end to end. {@code outcome} ∈
     * {@code completed | declined | replayed | conflict | exhausted | error}.
     */
    public void transferObserved(String engine, String outcome, long amountPaise, long nanos) {
        Timer.builder("wallet.transfer.duration")
                .description("End-to-end transfer time by engine and outcome")
                .tag("engine", engine).tag("outcome", outcome)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
        if ("completed".equals(outcome)) {
            transferAmount.record(amountPaise);
        }
    }
}
