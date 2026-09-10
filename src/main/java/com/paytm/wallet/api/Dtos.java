package com.paytm.wallet.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Request/response payloads. snake_case on the wire (see Jackson config in application.yml). */
public final class Dtos {

    public record CreateWalletRequest(@NotBlank String userId) {}

    public record WalletResponse(UUID id, long balancePaise) {}

    public record CreateTransferRequest(
            @NotNull UUID from,
            @NotNull UUID to,
            @Positive long amountPaise,
            @NotBlank @Size(max = 200) String idempotencyKey) {}

    public record TransferResponse(
            UUID id,
            UUID from,
            UUID to,
            long amountPaise,
            String status,
            String declineReason) {

        public static TransferResponse of(com.paytm.wallet.domain.Transfer t) {
            return new TransferResponse(t.id(), t.fromWalletId(), t.toWalletId(),
                    t.amountPaise(), t.status().name(),
                    t.declineReason());
        }
    }

    public record ErrorResponse(String error, String message, String correlationId) {}

    private Dtos() {}
}
