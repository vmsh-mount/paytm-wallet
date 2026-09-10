package com.paytm.wallet.api;

import com.paytm.wallet.observability.CorrelationIdFilter;
import com.paytm.wallet.service.DomainExceptions;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
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
 * <p>409 (idempotency conflict) is wired in TASK-06 when that exception starts
 * being thrown.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(DomainExceptions.NotFound.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Dtos.ErrorResponse notFound(DomainExceptions.NotFound ex) {
        return error("not_found", ex.getMessage());
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

    private static Dtos.ErrorResponse error(String code, String message) {
        return new Dtos.ErrorResponse(code, message, MDC.get(CorrelationIdFilter.MDC_KEY));
    }
}
