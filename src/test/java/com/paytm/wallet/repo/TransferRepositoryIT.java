package com.paytm.wallet.repo;

import com.paytm.wallet.domain.Transfer;
import com.paytm.wallet.support.AbstractPostgresIT;
import com.paytm.wallet.support.TestSeed;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TransferRepositoryIT extends AbstractPostgresIT {

    @Autowired
    TransferRepository transfers;

    @Test
    void findByIdempotencyKey_hit_and_miss() {
        UUID a = TestSeed.wallet(jdbc, "a", 1_000L);
        UUID b = TestSeed.wallet(jdbc, "b", 0L);
        UUID id = TestSeed.transfer(jdbc, a, b, 250L, "key-xyz", "fp-xyz");

        Transfer t = transfers.findByIdempotencyKey("key-xyz").orElseThrow();
        assertThat(t.id()).isEqualTo(id);
        assertThat(t.fromWalletId()).isEqualTo(a);
        assertThat(t.toWalletId()).isEqualTo(b);
        assertThat(t.amountPaise()).isEqualTo(250L);
        assertThat(t.requestFingerprint()).isEqualTo("fp-xyz");
        assertThat(t.status()).isEqualTo(Transfer.Status.COMPLETED);
        assertThat(t.declineReason()).isNull();

        assertThat(transfers.findByIdempotencyKey("no-such-key")).isEmpty();
        assertThat(transfers.findById(id)).contains(t);
        assertThat(transfers.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void declined_transfer_carries_its_reason() {
        UUID a = TestSeed.wallet(jdbc, "a", 1L);
        UUID b = TestSeed.wallet(jdbc, "b", 0L);
        UUID id = TestSeed.transfer(jdbc, a, b, 999L, "k-declined", "fp",
                Transfer.Status.DECLINED, "insufficient_funds");

        Transfer t = transfers.findById(id).orElseThrow();
        assertThat(t.status()).isEqualTo(Transfer.Status.DECLINED);
        assertThat(t.declineReason()).isEqualTo("insufficient_funds");
    }
}
