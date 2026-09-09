package com.paytm.wallet.repo;

import com.paytm.wallet.domain.Wallet;
import com.paytm.wallet.support.AbstractPostgresIT;
import com.paytm.wallet.support.TestSeed;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WalletRepositoryIT extends AbstractPostgresIT {

    @Autowired
    WalletRepository wallets;

    @Test
    void findByUserId_roundtrips_the_row() {
        UUID id = TestSeed.wallet(jdbc, "alice", 500_00L);

        Wallet w = wallets.findByUserId("alice").orElseThrow();

        assertThat(w.id()).isEqualTo(id);
        assertThat(w.userId()).isEqualTo("alice");
        assertThat(w.balancePaise()).isEqualTo(500_00L);
        assertThat(w.createdAt()).isNotNull().isBeforeOrEqualTo(Instant.now());
        assertThat(wallets.findById(id)).contains(w);
    }

    @Test
    void misses_return_empty() {
        assertThat(wallets.findByUserId("nobody")).isEmpty();
        assertThat(wallets.findById(UUID.randomUUID())).isEmpty();
    }
}
