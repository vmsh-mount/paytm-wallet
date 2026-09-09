package com.paytm.wallet.observability;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Assigns a correlation id per request (honours inbound {@code X-Correlation-Id}),
 * puts it in the SLF4J MDC so every structured log line carries it, and echoes it
 * on the response.
 *
 * TODO(scaffold): flesh out; keep it first in the chain.
 */
@Component
@Order(Integer.MIN_VALUE)
public class CorrelationIdFilter implements Filter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlation_id";

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest http = (HttpServletRequest) req;
        String cid = http.getHeader(HEADER);
        if (cid == null || cid.isBlank()) {
            cid = UUID.randomUUID().toString();
        }
        try {
            MDC.put(MDC_KEY, cid);
            ((HttpServletResponse) res).setHeader(HEADER, cid);
            chain.doFilter(req, res);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
