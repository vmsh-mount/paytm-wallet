package com.paytm.wallet.service;

import com.paytm.wallet.domain.Wallet;
import com.paytm.wallet.observability.DomainEvents;
import com.paytm.wallet.repo.WalletRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Wallet lifecycle. {@link #getOrCreate(String)} is race-free: two concurrent
 * calls for the same user yield exactly one wallet. The guarantee is the
 * {@code UNIQUE(user_id)} index plus {@code INSERT ... ON CONFLICT DO NOTHING};
 * this method just does the insert then an unconditional re-read, so the winner
 * and every loser return the same row.
 */
@Service
public class WalletService {

    private final WalletRepository wallets;

    public WalletService(WalletRepository wallets) {
        this.wallets = wallets;
    }

    public Wallet getOrCreate(String userId) {
        boolean created = wallets.insertIfAbsent(userId);
        Wallet wallet = wallets.findByUserId(userId).orElseThrow(() -> new IllegalStateException(
                "wallet for user '" + userId + "' missing immediately after upsert"));
        if (created) {
            DomainEvents.walletCreated(wallet.id(), userId);
        }
        return wallet;
    }

    public Wallet get(UUID walletId) {
        return wallets.findById(walletId).orElseThrow(() ->
                new DomainExceptions.NotFound("wallet " + walletId + " not found"));
    }
}
