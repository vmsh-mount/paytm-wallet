package com.paytm.wallet.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Domain counters, exposed at {@code /actuator/prometheus}. Request rate,
 * latency p99 and error rate come for free from Spring Boot's
 * {@code http.server.requests} timer.
 */
@Component
public class WalletMetrics {

    private final MeterRegistry registry;
    private final Counter transfersCreated;
    private final Counter transfersDeclinedInsufficientFunds;
    private final Counter idempotentReplays;

    public WalletMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.transfersCreated = Counter.builder("wallet.transfers.created").register(registry);
        this.transfersDeclinedInsufficientFunds =
                Counter.builder("wallet.transfers.declined").tag("reason", "insufficient_funds").register(registry);
        this.idempotentReplays = Counter.builder("wallet.transfers.idempotent_replay").register(registry);
    }

    public void transferCreated() { transfersCreated.increment(); }
    public void declinedInsufficientFunds() { transfersDeclinedInsufficientFunds.increment(); }
    public void idempotentReplay() { idempotentReplays.increment(); }

    /** A serializable-isolation transaction was retried after a {@code 40001} conflict. */
    public void serializationRetry(String engine) {
        Counter.builder("wallet.transfer.retries").tag("engine", engine).register(registry).increment();
    }
}
