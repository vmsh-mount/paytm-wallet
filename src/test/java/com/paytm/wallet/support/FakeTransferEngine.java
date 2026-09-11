package com.paytm.wallet.support;

import com.paytm.wallet.service.transfer.Engine;
import com.paytm.wallet.service.transfer.TransferEngine;
import com.paytm.wallet.service.transfer.TransferOutcome;
import com.paytm.wallet.service.transfer.TransferRequest;

import java.util.concurrent.atomic.AtomicReference;

/** Controllable {@link TransferEngine} for unit tests. */
public class FakeTransferEngine implements TransferEngine {

    public final AtomicReference<TransferOutcome> result = new AtomicReference<>();
    public final AtomicReference<RuntimeException> error = new AtomicReference<>();
    public final AtomicReference<TransferRequest> lastRequest = new AtomicReference<>();

    @Override
    public TransferOutcome execute(TransferRequest request) {
        lastRequest.set(request);
        if (error.get() != null) {
            throw error.get();
        }
        return result.get();
    }

    @Override
    public Engine engineType() {
        return Engine.CONDITIONAL_UPDATE;
    }
}
