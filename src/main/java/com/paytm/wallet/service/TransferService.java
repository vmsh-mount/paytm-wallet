package com.paytm.wallet.service;

import com.paytm.wallet.domain.Transfer;
import com.paytm.wallet.domain.Wallet;
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
 * <p>Idempotency-key handling (replay → stored result, different body →
 * {@link DomainExceptions.IdempotencyConflict} → 409) lives in the engine, inside
 * the money-movement transaction. Domain counters are the engine's too, since it
 * is the thing that knows whether an effect actually happened or was replayed.
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final TransferEngine engine;
    private final TransferRepository transfers;
    private final WalletRepository wallets;

    public TransferService(TransferEngine engine, TransferRepository transfers,
                           WalletRepository wallets) {
        this.engine = engine;
        this.transfers = transfers;
        this.wallets = wallets;
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

        return engine.execute(request, correlationId);
    }

    public Transfer get(UUID transferId) {
        return transfers.findById(transferId).orElseThrow(() ->
                new DomainExceptions.NotFound("transfer " + transferId + " not found"));
    }
}
