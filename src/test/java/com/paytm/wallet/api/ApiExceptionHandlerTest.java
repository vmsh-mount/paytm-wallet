package com.paytm.wallet.api;

import com.paytm.wallet.observability.CorrelationIdFilter;
import com.paytm.wallet.service.DomainExceptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @BeforeEach
    void setCorrelationId() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "cid-123");
    }

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    void not_found_maps_to_a_body_with_the_correlation_id() {
        Dtos.ErrorResponse body = handler.notFound(new DomainExceptions.NotFound("wallet x"));
        assertThat(body.error()).isEqualTo("not_found");
        assertThat(body.message()).isEqualTo("wallet x");
        assertThat(body.correlationId()).isEqualTo("cid-123");
    }

    @Test
    void idempotency_conflict_body() {
        assertThat(handler.idempotencyConflict(new DomainExceptions.IdempotencyConflict("dup")).error())
                .isEqualTo("idempotency_conflict");
    }

    @Test
    void invalid_transfer_and_not_owner_bodies() {
        assertThat(handler.invalidTransfer(new DomainExceptions.InvalidTransfer("bad")).error())
                .isEqualTo("bad_request");
        assertThat(handler.notOwner(new DomainExceptions.NotWalletOwner("nope")).error())
                .isEqualTo("forbidden");
    }

    @Test
    void unexpected_returns_500_without_leaking_the_message() throws Exception {
        var response = handler.unexpected(new IllegalStateException("boom secret detail"));
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().error()).isEqualTo("internal_error");
        assertThat(response.getBody().message()).doesNotContain("secret");
        assertThat(response.getBody().correlationId()).isEqualTo("cid-123");
    }

    @Test
    void unexpected_rethrows_framework_error_responses() {
        var framework = new ResponseStatusException(org.springframework.http.HttpStatus.METHOD_NOT_ALLOWED);
        assertThatThrownBy(() -> handler.unexpected(framework)).isSameAs(framework);
    }
}
