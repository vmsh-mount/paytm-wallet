package com.paytm.wallet.service;

import com.paytm.wallet.domain.Transfer;
import com.paytm.wallet.observability.WalletMetrics;
import com.paytm.wallet.service.transfer.TransferEngine;
import com.paytm.wallet.service.transfer.TransferRequest;
import com.paytm.wallet.support.FakeTransferRepository;
import com.paytm.wallet.support.FakeWalletRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferServiceTest {

    private final FakeWalletRepository walletRepo = new FakeWalletRepository();
    private final FakeTransferRepository transferRepo = new FakeTransferRepository();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final WalletMetrics metrics = new WalletMetrics(registry);

    private final AtomicReference<Transfer> engineResult = new AtomicReference<>();
    private final AtomicReference<TransferRequest> engineSaw = new AtomicReference<>();
    private final TransferEngine engine = (request, correlationId) -> {
        engineSaw.set(request);
        return engineResult.get();
    };
    private final TransferService service =
            new TransferService(engine, transferRepo, walletRepo, metrics);

    private UUID wallet(String user, long balance) {
        walletRepo.insertIfAbsent(user);
        return walletRepo.findByUserId(user).orElseThrow().id();
    }

    private static TransferRequest req(UUID from, UUID to, long amount) {
        return new TransferRequest(from, to, amount, UUID.randomUUID().toString());
    }

    private Transfer canned(UUID from, UUID to, long amount, Transfer.Status status, String reason) {
        return new Transfer(UUID.randomUUID(), from, to, amount, "k", "fp", status, reason, Instant.now());
    }

    @Test
    void non_positive_amount_is_rejected() {
        UUID a = wallet("a", 100);
        UUID b = wallet("b", 0);
        assertThatThrownBy(() -> service.create(req(a, b, 0), "a", "cid"))
                .isInstanceOf(DomainExceptions.InvalidTransfer.class);
        assertThatThrownBy(() -> service.create(req(a, b, -5), "a", "cid"))
                .isInstanceOf(DomainExceptions.InvalidTransfer.class);
    }

    @Test
    void from_equals_to_is_rejected() {
        UUID a = wallet("a", 100);
        assertThatThrownBy(() -> service.create(req(a, a, 10), "a", "cid"))
                .isInstanceOf(DomainExceptions.InvalidTransfer.class);
    }

    @Test
    void unknown_wallet_is_NotFound() {
        UUID a = wallet("a", 100);
        assertThatThrownBy(() -> service.create(req(a, UUID.randomUUID(), 10), "a", "cid"))
                .isInstanceOf(DomainExceptions.NotFound.class);
        assertThatThrownBy(() -> service.create(req(UUID.randomUUID(), a, 10), "a", "cid"))
                .isInstanceOf(DomainExceptions.NotFound.class);
    }

    @Test
    void caller_must_own_the_source_wallet() {
        UUID a = wallet("alice", 100);
        UUID b = wallet("bob", 0);
        assertThatThrownBy(() -> service.create(req(a, b, 10), "bob", "cid"))
                .isInstanceOf(DomainExceptions.NotWalletOwner.class);
    }

    @Test
    void completed_transfer_increments_created_counter() {
        UUID a = wallet("alice", 100);
        UUID b = wallet("bob", 0);
        engineResult.set(canned(a, b, 30, Transfer.Status.COMPLETED, null));

        Transfer result = service.create(req(a, b, 30), "alice", "cid");

        assertThat(result.status()).isEqualTo(Transfer.Status.COMPLETED);
        assertThat(engineSaw.get().amountPaise()).isEqualTo(30);
        assertThat(registry.get("wallet.transfers.created").counter().count()).isEqualTo(1.0);
    }

    @Test
    void declined_transfer_increments_declined_counter_not_created() {
        UUID a = wallet("alice", 10);
        UUID b = wallet("bob", 0);
        engineResult.set(canned(a, b, 50, Transfer.Status.DECLINED, "insufficient_funds"));

        Transfer result = service.create(req(a, b, 50), "alice", "cid");

        assertThat(result.status()).isEqualTo(Transfer.Status.DECLINED);
        assertThat(registry.get("wallet.transfers.declined").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("wallet.transfers.created").counter().count()).isZero();
    }
}
