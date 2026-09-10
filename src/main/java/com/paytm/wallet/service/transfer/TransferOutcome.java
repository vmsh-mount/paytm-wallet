package com.paytm.wallet.service.transfer;

import com.paytm.wallet.domain.Transfer;

/**
 * What an engine did with a request.
 *
 * @param transfer the resulting (or previously stored) transfer row
 * @param replayed true if this call did NOT move money — the {@code idempotency_key}
 *                 was already applied and {@code transfer} is the stored result.
 *                 Drives the HTTP code: fresh effect → {@code 201}, replay → {@code 200}.
 */
public record TransferOutcome(Transfer transfer, boolean replayed) {

    public static TransferOutcome fresh(Transfer transfer) {
        return new TransferOutcome(transfer, false);
    }

    public static TransferOutcome replay(Transfer transfer) {
        return new TransferOutcome(transfer, true);
    }
}
