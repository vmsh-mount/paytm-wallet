package com.paytm.wallet.idempotency;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RequestFingerprintTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    @Test
    void is_stable_and_64_hex_lowercase() {
        String fp = RequestFingerprint.of(A, B, 1500);
        assertThat(fp).isEqualTo(RequestFingerprint.of(A, B, 1500));
        assertThat(fp).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void differs_when_amount_differs() {
        assertThat(RequestFingerprint.of(A, B, 1500))
                .isNotEqualTo(RequestFingerprint.of(A, B, 1501));
    }

    @Test
    void differs_when_direction_is_reversed() {
        assertThat(RequestFingerprint.of(A, B, 1500))
                .isNotEqualTo(RequestFingerprint.of(B, A, 1500));
    }
}
