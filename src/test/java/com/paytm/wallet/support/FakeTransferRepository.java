package com.paytm.wallet.support;

import com.paytm.wallet.domain.Transfer;
import com.paytm.wallet.repo.TransferRepository;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** In-memory {@link TransferRepository} for unit tests. */
public class FakeTransferRepository extends TransferRepository {

    public final Map<UUID, Transfer> byId = new HashMap<>();

    public FakeTransferRepository() {
        super(null);
    }

    @Override
    public Optional<Transfer> findById(UUID id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<Transfer> findByIdempotencyKey(String key) {
        return byId.values().stream().filter(t -> key.equals(t.idempotencyKey())).findFirst();
    }
}
