package com.paytm.wallet.config;

import java.util.Optional;

/**
 * Per-request identity, populated by {@link AuthFilter} and cleared when the
 * request completes. {@code ThreadLocal} rather than a request-scoped bean: the
 * request is handled on one thread and this avoids a proxy on every injection
 * point.
 *
 * <p>Bound to the request thread only — it does not follow an async dispatch or
 * {@code @Async} hand-off. All current endpoints are synchronous; revisit if that
 * changes.
 */
public final class RequestContext {

    private static final ThreadLocal<RequestContext> HOLDER = new ThreadLocal<>();

    private final String userId;
    private final String correlationId;

    private RequestContext(String userId, String correlationId) {
        this.userId = userId;
        this.correlationId = correlationId;
    }

    static void set(String userId, String correlationId) {
        HOLDER.set(new RequestContext(userId, correlationId));
    }

    static void clear() {
        HOLDER.remove();
    }

    public static Optional<RequestContext> current() {
        return Optional.ofNullable(HOLDER.get());
    }

    /** The authenticated caller's user id. Throws if called outside an authenticated request. */
    public static String userId() {
        RequestContext ctx = HOLDER.get();
        if (ctx == null) {
            throw new IllegalStateException("no RequestContext bound to this thread");
        }
        return ctx.userId;
    }

    public String getUserId() {
        return userId;
    }

    public String getCorrelationId() {
        return correlationId;
    }
}
