package com.paytm.wallet.service;

import com.paytm.wallet.domain.Wallet;
import com.paytm.wallet.repo.WalletRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Wallet lifecycle. {@link #getOrCreate(String)} must be race-free: two
 * concurrent calls for the same user yield exactly one wallet. Enforced by a
 * UNIQUE constraint on {@code wallets.user_id} + INSERT ... ON CONFLICT DO
 * NOTHING, then re-select.
 */
@Service
public class WalletService {

    private final WalletRepository wallets;

    public WalletService(WalletRepository wallets) {
        this.wallets = wallets;
    }

    public Wallet getOrCreate(String userId) {
        throw new UnsupportedOperationException("scaffold: WalletService.getOrCreate not implemented");
    }

    public Wallet get(UUID walletId) {
        throw new UnsupportedOperationException("scaffold: WalletService.get not implemented");
    }
}
