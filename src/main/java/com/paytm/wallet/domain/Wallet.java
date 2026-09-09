package com.paytm.wallet.domain;

import java.time.Instant;
import java.util.UUID;

/** A user wallet. Balance is always integer paise, never a float. */
public record Wallet(UUID id, String userId, long balancePaise, Instant createdAt) {
}
