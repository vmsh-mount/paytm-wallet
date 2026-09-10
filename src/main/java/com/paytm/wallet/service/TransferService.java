package com.paytm.wallet.service;

import com.paytm.wallet.domain.Transfer;
import com.paytm.wallet.domain.Wallet;
import com.paytm.wallet.observability.WalletMetrics;
import com.paytm.wallet.repo.TransferRepository;
import com.paytm.wallet.repo.WalletRepository;
import com.paytm.wallet.service.transfer.TransferEngine;
import com.paytm.wallet.service.transfer.TransferRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Orchestrates a transfer: validate (positive amount, distinct wallets, both
 * wallets exist, caller owns the source), then delegate the atomic money
 * movement to the active {@link TransferEngine}. Insufficient funds is a
 * <em>value</em> — {@code Transfer{status=DECLINED}} — not an exception.
 *
 * <p>Idempotency-key handling (replay → original result, different body → 409)
 * lands in TASK-05; for now each call is assumed to carry a fresh key.
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final TransferEngine engine;
    private final TransferRepository transfers;
    private final WalletRepository wallets;
    private final WalletMetrics metrics;

    public TransferService(TransferEngine engine, TransferRepository transfers,
                           WalletRepository wallets, WalletMetrics metrics) {
        this.engine = engine;
        this.transfers = transfers;
        this.wallets = wallets;
        this.metrics = metrics;
    }

    public Transfer create(TransferRequest request, String callerUserId, String correlationId) {
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

        log.atInfo().addKeyValue("event", "transfer.created")
                .addKeyValue("from_wallet_id", request.fromWalletId())
                .addKeyValue("to_wallet_id", request.toWalletId())
                .addKeyValue("amount_paise", request.amountPaise())
                .addKeyValue("idempotency_key", request.idempotencyKey())
                .log("transfer requested");

        Transfer result = engine.execute(request, correlationId);

        switch (result.status()) {
            case COMPLETED -> metrics.transferCreated();
            case DECLINED -> metrics.declinedInsufficientFunds();
            case CREATED -> { /* not produced by the engine */ }
        }
        return result;
    }

    public Transfer get(UUID transferId) {
        return transfers.findById(transferId).orElseThrow(() ->
                new DomainExceptions.NotFound("transfer " + transferId + " not found"));
    }
}
