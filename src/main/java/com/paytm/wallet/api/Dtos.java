package com.paytm.wallet.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

/** Request/response payloads. snake_case on the wire (see Jackson config in application.yml). */
public final class Dtos {

    public record CreateWalletRequest(@NotBlank String userId) {}

    public record WalletResponse(UUID id, long balancePaise) {}

    public record CreateTransferRequest(
            @NotNull UUID from,
            @NotNull UUID to,
            @Positive long amountPaise,
            @NotBlank String idempotencyKey) {}

    public record TransferResponse(
            UUID id,
            UUID from,
            UUID to,
            long amountPaise,
            String status,
            String declineReason) {}

    public record ErrorResponse(String error, String message, String correlationId) {}

    private Dtos() {}
}
