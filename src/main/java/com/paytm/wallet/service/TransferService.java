package com.paytm.wallet.service;

import com.paytm.wallet.domain.Transfer;
import com.paytm.wallet.domain.Wallet;
import com.paytm.wallet.observability.CorrelationIdFilter;
import com.paytm.wallet.observability.DomainEvents;
import com.paytm.wallet.repo.TransferRepository;
import com.paytm.wallet.repo.WalletRepository;
import com.paytm.wallet.service.transfer.TransferEngine;
import com.paytm.wallet.service.transfer.TransferOutcome;
import com.paytm.wallet.service.transfer.TransferRequest;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Orchestrates a transfer: validate (positive amount, distinct wallets, both
 * wallets exist, caller owns the source), then delegate the atomic money
 * movement to the active {@link TransferEngine}. Insufficient funds is a
 * <em>value</em> — {@code Transfer{status=DECLINED}} — not an exception.
 *
 * <p>Idempotency-key handling (replay → stored result, different body →
 * {@link DomainExceptions.IdempotencyConflict} → 409) lives in the engine, inside
 * the money-movement transaction. Domain counters are the engine's too, since it
 * is the thing that knows whether an effect actually happened or was replayed.
 */
@Service
public class TransferService {

    private final TransferEngine engine;
    private final TransferRepository transfers;
    private final WalletRepository wallets;

    public TransferService(TransferEngine engine, TransferRepository transfers,
                           WalletRepository wallets) {
        this.engine = engine;
        this.transfers = transfers;
        this.wallets = wallets;
    }

    public TransferOutcome create(TransferRequest request, String callerUserId, String correlationId) {
        // Ensure a correlation id is in the MDC even for direct (non-HTTP) callers,
        // so every DomainEvents line for this transfer is correlated.
        boolean ownsMdc = MDC.get(CorrelationIdFilter.MDC_KEY) == null && correlationId != null;
        if (ownsMdc) {
            MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
        }
        try {
            return doCreate(request, callerUserId);
        } finally {
            if (ownsMdc) {
                MDC.remove(CorrelationIdFilter.MDC_KEY);
            }
        }
    }

    private TransferOutcome doCreate(TransferRequest request, String callerUserId) {
        if (request.amountPaise() <= 0) {
            throw new DomainExceptions.InvalidTransfer("amount_paise must be positive");
        }
        if (request.fromWalletId().equals(request.toWalletId())) {
            throw new DomainExceptions.InvalidTransfer("from and to must be different wallets");
        }
        Wallet from = wallets.findById(request.fromWalletId()).orElseThrow(() ->
                new DomainExceptions.NotFound("wallet " + request.fromWalletId() + " not found"));
        wallets.findById(request.toWalletId()).orElseThrow(() ->
                new DomainExceptions.NotFound("wallet " + request.toWalletId() + " not found"));
        if (!from.userId().equals(callerUserId)) {
            throw new DomainExceptions.NotWalletOwner(
                    "caller does not own source wallet " + from.id());
        }

        DomainEvents.transferReceived(request.fromWalletId(), request.toWalletId(),
                request.amountPaise(), request.idempotencyKey());

        long startNanos = System.nanoTime();
        TransferOutcome outcome = engine.execute(request);
        if (!outcome.replayed() && outcome.transfer().status() == Transfer.Status.COMPLETED) {
            DomainEvents.transferCompleted(outcome.transfer().id(), request.amountPaise(),
                    (System.nanoTime() - startNanos) / 1_000_000.0);
        }
        return outcome;
    }

    public Transfer get(UUID transferId) {
        return transfers.findById(transferId).orElseThrow(() ->
                new DomainExceptions.NotFound("transfer " + transferId + " not found"));
    }
}
