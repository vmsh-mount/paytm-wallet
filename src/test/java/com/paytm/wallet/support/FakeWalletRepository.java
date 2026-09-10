package com.paytm.wallet.support;

import com.paytm.wallet.domain.Wallet;
import com.paytm.wallet.repo.WalletRepository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory {@link WalletRepository} for unit tests — models the
 * {@code ON CONFLICT DO NOTHING} semantics without a database (and without a
 * mocking framework, which the local JDK can't instrument).
 */
public class FakeWalletRepository extends WalletRepository {

    private final Map<String, Wallet> byUser = new LinkedHashMap<>();
    public int insertIfAbsentCalls;

    public FakeWalletRepository() {
        super(null);
    }

    @Override
    public boolean insertIfAbsent(String userId) {
        insertIfAbsentCalls++;
        if (byUser.containsKey(userId)) {
            return false;
        }
        byUser.put(userId, new Wallet(UUID.randomUUID(), userId, 0L, Instant.now()));
        return true;
    }

    @Override
    public Optional<Wallet> findByUserId(String userId) {
        return Optional.ofNullable(byUser.get(userId));
    }

    @Override
    public Optional<Wallet> findById(UUID id) {
        return byUser.values().stream().filter(w -> w.id().equals(id)).findFirst();
    }
}
