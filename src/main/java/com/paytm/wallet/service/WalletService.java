package com.paytm.wallet.service;

import com.paytm.wallet.domain.Wallet;
import com.paytm.wallet.repo.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private final WalletRepository wallets;

    public WalletService(WalletRepository wallets) {
        this.wallets = wallets;
    }

    public Wallet getOrCreate(String userId) {
        boolean created = wallets.insertIfAbsent(userId);
        Wallet wallet = wallets.findByUserId(userId).orElseThrow(() -> new IllegalStateException(
                "wallet for user '" + userId + "' missing immediately after upsert"));
        if (created) {
            log.atInfo()
                    .addKeyValue("event", "wallet.created")
                    .addKeyValue("wallet_id", wallet.id())
                    .log("wallet created");
        }
        return wallet;
    }

    public Wallet get(UUID walletId) {
        Wallet wallet = wallets.findById(walletId).orElseThrow(() ->
                new DomainExceptions.NotFound("wallet " + walletId + " not found"));
        log.atInfo()
                .addKeyValue("event", "wallet.fetched")
                .addKeyValue("wallet_id", wallet.id())
                .log("wallet fetched");
        return wallet;
    }
}
