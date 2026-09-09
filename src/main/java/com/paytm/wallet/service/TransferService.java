package com.paytm.wallet.service;

import com.paytm.wallet.domain.Transfer;
import com.paytm.wallet.repo.TransferRepository;
import com.paytm.wallet.service.transfer.TransferEngine;
import com.paytm.wallet.service.transfer.TransferRequest;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Orchestrates a transfer: validate (positive amount, distinct wallets, caller
 * owns source), then delegate the atomic money movement to the active
 * {@link TransferEngine}. Same-key/same-body retry returns the original result;
 * same-key/different-body raises a 409 conflict.
 */
@Service
public class TransferService {

    private final TransferEngine engine;
    private final TransferRepository transfers;

    public TransferService(TransferEngine engine, TransferRepository transfers) {
        this.engine = engine;
        this.transfers = transfers;
    }

    public Transfer create(TransferRequest request, String callerUserId, String correlationId) {
        throw new UnsupportedOperationException("scaffold: TransferService.create not implemented");
    }

    public Transfer get(UUID transferId) {
        throw new UnsupportedOperationException("scaffold: TransferService.get not implemented");
    }
}
