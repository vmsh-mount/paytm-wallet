package com.paytm.wallet.service;

import com.paytm.wallet.domain.Transfer;
import com.paytm.wallet.service.transfer.TransferEngine;
import com.paytm.wallet.service.transfer.TransferOutcome;
import com.paytm.wallet.service.transfer.TransferRequest;
import com.paytm.wallet.support.FakeTransferRepository;
import com.paytm.wallet.support.FakeWalletRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferServiceTest {

    private final FakeWalletRepository walletRepo = new FakeWalletRepository();
    private final FakeTransferRepository transferRepo = new FakeTransferRepository();

    private final AtomicReference<TransferOutcome> engineResult = new AtomicReference<>();
    private final AtomicReference<RuntimeException> engineThrows = new AtomicReference<>();
    private final AtomicReference<TransferRequest> engineSaw = new AtomicReference<>();
    private final TransferEngine engine = request -> {
        engineSaw.set(request);
        if (engineThrows.get() != null) {
            throw engineThrows.get();
        }
        return engineResult.get();
    };
    private final TransferService service = new TransferService(engine, transferRepo, walletRepo);

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
    void returns_the_engine_outcome() {
        UUID a = wallet("alice");
        UUID b = wallet("bob");
        engineResult.set(TransferOutcome.fresh(canned(a, b, 30, Transfer.Status.COMPLETED, null)));

        TransferOutcome result = service.create(req(a, b, 30), "alice", "cid");

        assertThat(result.transfer().status()).isEqualTo(Transfer.Status.COMPLETED);
        assertThat(result.replayed()).isFalse();
        assertThat(engineSaw.get().amountPaise()).isEqualTo(30);
    }

    @Test
    void idempotency_conflict_from_the_engine_propagates() {
        UUID a = wallet("alice");
        UUID b = wallet("bob");
        engineThrows.set(new DomainExceptions.IdempotencyConflict("key reused"));

        assertThatThrownBy(() -> service.create(req(a, b, 30), "alice", "cid"))
                .isInstanceOf(DomainExceptions.IdempotencyConflict.class);
    }
}
