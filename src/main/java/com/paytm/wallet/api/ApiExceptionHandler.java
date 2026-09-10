package com.paytm.wallet.api;

import com.paytm.wallet.observability.CorrelationIdFilter;
import com.paytm.wallet.service.DomainExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Maps domain + framework exceptions to a uniform JSON error
 * {@code {error, message, correlation_id}}. The correlation id comes from the MDC
 * set by {@link CorrelationIdFilter}.
 *
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(DomainExceptions.NotFound.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Dtos.ErrorResponse notFound(DomainExceptions.NotFound ex) {
        return error("not_found", ex.getMessage());
    }

    @ExceptionHandler(DomainExceptions.IdempotencyConflict.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Dtos.ErrorResponse idempotencyConflict(DomainExceptions.IdempotencyConflict ex) {
        return error("idempotency_conflict", ex.getMessage());
    }

    @ExceptionHandler(DomainExceptions.SerializationExhausted.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Dtos.ErrorResponse serializationExhausted(DomainExceptions.SerializationExhausted ex) {
        return error("serialization_failure", "too much contention, please retry");
    }

    @ExceptionHandler(DomainExceptions.InvalidTransfer.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Dtos.ErrorResponse invalidTransfer(DomainExceptions.InvalidTransfer ex) {
        return error("bad_request", ex.getMessage());
    }

    @ExceptionHandler(DomainExceptions.NotWalletOwner.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Dtos.ErrorResponse notOwner(DomainExceptions.NotWalletOwner ex) {
        return error("forbidden", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Dtos.ErrorResponse invalidBody(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .orElse("request validation failed");
        return error("bad_request", detail);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Dtos.ErrorResponse malformed(Exception ex) {
        return error("bad_request", "malformed request");
    }

    /**
     * Catch-all → {@code 500}, correlation id only (no stack trace to the client;
     * the stack goes to the logs). Framework exceptions that already carry an
     * HTTP status ({@link ErrorResponse}: 404 for an unknown path, 405, …) are
     * rethrown so Spring renders them.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Dtos.ErrorResponse> unexpected(Exception ex) throws Exception {
        if (ex instanceof ErrorResponse) {
            throw ex;
        }
        log.error("unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error("internal_error", "an unexpected error occurred"));
    }

    private static Dtos.ErrorResponse error(String code, String message) {
        return new Dtos.ErrorResponse(code, message, MDC.get(CorrelationIdFilter.MDC_KEY));
    }
}
