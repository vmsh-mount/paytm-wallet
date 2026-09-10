package com.paytm.wallet.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paytm.wallet.api.Dtos;
import com.paytm.wallet.observability.CorrelationIdFilter;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;

/**
 * Minimal bearer-token auth: {@code Authorization: Bearer <token>} is resolved to
 * a {@code userId} from {@link AuthProperties} and bound to {@link RequestContext}
 * (and the log MDC) for the duration of the request. Missing / malformed /
 * unknown token → {@code 401} with a JSON error carrying the correlation id.
 *
 * <p>A plain servlet {@code Filter}, not Spring Security — this maps one header to
 * one string; the Security autoconfig surface would be far more to defend.
 * Open paths: {@code /actuator/**}. Ownership checks (caller owns the source
 * wallet) live in the service layer, not here.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10) // after CorrelationIdFilter (HIGHEST_PRECEDENCE)
public class AuthFilter implements Filter {

    /** MDC key for the resolved caller; never the token. */
    public static final String MDC_USER_ID = "user_id";
    private static final String BEARER_PREFIX = "Bearer ";

    private static final Logger log = LoggerFactory.getLogger(AuthFilter.class);

    private final Map<String, String> tokenToUserId;
    private final ObjectMapper objectMapper;

    public AuthFilter(AuthProperties properties, ObjectMapper objectMapper) {
        this.tokenToUserId = properties.tokenToUserId(); // validated here → bad config fails startup
        this.objectMapper = objectMapper;
        if (this.tokenToUserId.isEmpty()) {
            log.warn("wallet.auth.tokens is empty — every authenticated endpoint will return 401");
        }
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        if (isOpenPath(request)) {
            chain.doFilter(req, res);
            return;
        }

        String userId = resolveUser(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (userId == null) {
            log.info("auth rejected: {} {}", request.getMethod(), request.getRequestURI());
            writeUnauthorized(response);
            return;
        }

        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        RequestContext.set(userId, correlationId);
        MDC.put(MDC_USER_ID, userId);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove(MDC_USER_ID);
            RequestContext.clear();
        }
    }

    private static boolean isOpenPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator/") || path.equals("/actuator");
    }

    /** @return the resolved userId, or {@code null} if the header is missing/malformed/unknown. */
    private String resolveUser(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String presented = authorizationHeader.substring(BEARER_PREFIX.length()).strip();
        if (presented.isEmpty()) {
            return null;
        }
        byte[] presentedBytes = presented.getBytes(StandardCharsets.UTF_8);
        String match = null;
        // Compare against every entry so timing doesn't reveal which token (if any) matched.
        for (Map.Entry<String, String> entry : tokenToUserId.entrySet()) {
            boolean equal = MessageDigest.isEqual(
                    presentedBytes, entry.getKey().getBytes(StandardCharsets.UTF_8));
            if (equal) {
                match = entry.getValue();
            }
        }
        return match;
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), new Dtos.ErrorResponse(
                "unauthorized", "missing or invalid bearer token", correlationId));
    }
}
