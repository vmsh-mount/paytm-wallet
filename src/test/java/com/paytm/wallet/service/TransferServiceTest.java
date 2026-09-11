package com.paytm.wallet.service;

import com.paytm.wallet.domain.Transfer;
import com.paytm.wallet.observability.WalletMetrics;
import com.paytm.wallet.service.transfer.TransferOutcome;
import com.paytm.wallet.service.transfer.TransferRequest;
import com.paytm.wallet.support.FakeTransferEngine;
import com.paytm.wallet.support.FakeTransferRepository;
import com.paytm.wallet.support.FakeWalletRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferServiceTest {

    private final FakeWalletRepository walletRepo = new FakeWalletRepository();
    private final FakeTransferRepository transferRepo = new FakeTransferRepository();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final FakeTransferEngine engine = new FakeTransferEngine();
    private final TransferService service =
            new TransferService(engine, transferRepo, walletRepo, new WalletMetrics(registry));

    private UUID wallet(String user) {
        walletRepo.insertIfAbsent(user);
        return walletRepo.findByUserId(user).orElseThrow().id();
    }

    private static TransferRequest req(UUID from, UUID to, long amount) {
        return new TransferRequest(from, to, amount, UUID.randomUUID().toString());
    }

    private static Transfer canned(UUID from, UUID to, long amount, Transfer.Status status, String reason) {
        return new Transfer(UUID.randomUUID(), from, to, amount, "k", "fp", status, reason, Instant.now());
    }

    @Test
    void non_positive_amount_is_rejected() {
        UUID a = wallet("a");
        UUID b = wallet("b");
        assertThatThrownBy(() -> service.create(req(a, b, 0), "a", "cid"))
                .isInstanceOf(DomainExceptions.InvalidTransfer.class);
        assertThatThrownBy(() -> service.create(req(a, b, -5), "a", "cid"))
                .isInstanceOf(DomainExceptions.InvalidTransfer.class);
    }

    @Test
    void from_equals_to_is_rejected() {
        UUID a = wallet("a");
        assertThatThrownBy(() -> service.create(req(a, a, 10), "a", "cid"))
                .isInstanceOf(DomainExceptions.InvalidTransfer.class);
    }

    @Test
    void unknown_wallet_is_NotFound() {
        UUID a = wallet("a");
        assertThatThrownBy(() -> service.create(req(a, UUID.randomUUID(), 10), "a", "cid"))
                .isInstanceOf(DomainExceptions.NotFound.class);
        assertThatThrownBy(() -> service.create(req(UUID.randomUUID(), a, 10), "a", "cid"))
                .isInstanceOf(DomainExceptions.NotFound.class);
    }

    @Test
    void caller_must_own_the_source_wallet() {
        UUID a = wallet("alice");
        UUID b = wallet("bob");
        assertThatThrownBy(() -> service.create(req(a, b, 10), "bob", "cid"))
                .isInstanceOf(DomainExceptions.NotWalletOwner.class);
    }

    @Test
    void returns_the_engine_outcome_and_records_a_tagged_timer() {
        UUID a = wallet("alice");
        UUID b = wallet("bob");
        engine.result.set(TransferOutcome.fresh(canned(a, b, 30, Transfer.Status.COMPLETED, null)));

        TransferOutcome result = service.create(req(a, b, 30), "alice", "cid");

        assertThat(result.transfer().status()).isEqualTo(Transfer.Status.COMPLETED);
        assertThat(result.replayed()).isFalse();
        assertThat(engine.lastRequest.get().amountPaise()).isEqualTo(30);
        assertThat(registry.get("wallet.transfer.duration")
                .tag("engine", "conditional-update").tag("outcome", "completed").timer().count())
                .isEqualTo(1L);
        assertThat(registry.get("wallet.transfer.amount").summary().count()).isEqualTo(1L);
    }

    @Test
    void conflict_is_timed_with_the_conflict_outcome_tag() {
        UUID a = wallet("alice");
        UUID b = wallet("bob");
        engine.error.set(new DomainExceptions.IdempotencyConflict("dup"));

        assertThatThrownBy(() -> service.create(req(a, b, 30), "alice", "cid"))
                .isInstanceOf(DomainExceptions.IdempotencyConflict.class);
        assertThat(registry.get("wallet.transfer.duration").tag("outcome", "conflict").timer().count())
                .isEqualTo(1L);
    }

    @Test
    void idempotency_conflict_from_the_engine_propagates() {
        UUID a = wallet("alice");
        UUID b = wallet("bob");
        engine.error.set(new DomainExceptions.IdempotencyConflict("key reused"));

        assertThatThrownBy(() -> service.create(req(a, b, 30), "alice", "cid"))
                .isInstanceOf(DomainExceptions.IdempotencyConflict.class);
    }
}
