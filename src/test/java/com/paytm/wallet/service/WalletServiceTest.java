package com.paytm.wallet.service;

import com.paytm.wallet.domain.Wallet;
import com.paytm.wallet.repo.WalletRepository;
import com.paytm.wallet.support.FakeWalletRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalletServiceTest {

    private final FakeWalletRepository repo = new FakeWalletRepository();
    private final WalletService service = new WalletService(repo);

    @Test
    void getOrCreate_creates_then_reads_and_is_idempotent() {
        Wallet first = service.getOrCreate("alice");
        assertThat(first.balancePaise()).isZero();

        Wallet again = service.getOrCreate("alice");
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(repo.insertIfAbsentCalls).isEqualTo(2); // insert always attempted...
        assertThat(service.get(first.id()).id()).isEqualTo(first.id()); // ...but only one row
    }

    @Test
    void getOrCreate_guards_against_a_vanished_row() {
        // repo that claims it created the row but then can't find it
        WalletRepository broken = new FakeWalletRepository() {
            @Override
            public Optional<Wallet> findByUserId(String userId) {
                return Optional.empty();
            }
        };
        assertThatThrownBy(() -> new WalletService(broken).getOrCreate("ghost"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void get_unknown_id_is_NotFound() {
        assertThatThrownBy(() -> service.get(UUID.randomUUID()))
                .isInstanceOf(DomainExceptions.NotFound.class);
    }
}
