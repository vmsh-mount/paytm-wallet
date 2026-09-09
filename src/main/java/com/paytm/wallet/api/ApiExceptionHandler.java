package com.paytm.wallet.api;

import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps domain exceptions to HTTP:
 *   - validation / bad input        -> 400
 *   - unknown wallet / transfer     -> 404
 *   - caller not owner of source    -> 403
 *   - same idempotency key, diff body -> 409
 *   - insufficient funds is NOT an error: 200/201 with status=DECLINED
 *
 * TODO(scaffold): implement handlers, always include the correlation id.
 */
@RestControllerAdvice
public class ApiExceptionHandler {
}
