package com.paytm.wallet.service.transfer;

import java.util.UUID;

/** Validated, internal representation of a transfer request. */
public record TransferRequest(
        UUID fromWalletId,
        UUID toWalletId,
        long amountPaise,
        String idempotencyKey) {
}
