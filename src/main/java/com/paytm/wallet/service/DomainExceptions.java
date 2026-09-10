package com.paytm.wallet.service;

/** Domain-level failures. Mapped to HTTP by {@code ApiExceptionHandler}. */
public final class DomainExceptions {

    public static class NotFound extends RuntimeException {
        public NotFound(String msg) { super(msg); }
    }

    /** Request is structurally invalid (non-positive amount, from == to). -> 400 */
    public static class InvalidTransfer extends RuntimeException {
        public InvalidTransfer(String msg) { super(msg); }
    }

    /** Caller's bearer token does not own the source wallet. -> 403 */
    public static class NotWalletOwner extends RuntimeException {
        public NotWalletOwner(String msg) { super(msg); }
    }

    /** Same idempotency_key seen with a different request body. -> 409 */
    public static class IdempotencyConflict extends RuntimeException {
        public IdempotencyConflict(String msg) { super(msg); }
    }

    private DomainExceptions() {}
}
