package com.paytm.wallet.config;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.util.StringUtils;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
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
    /** Request attribute holding the resolved caller — outlives the MDC, so the access log can read it. */
    public static final String USER_ID_ATTRIBUTE = "wallet.userId";
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
        request.setAttribute(USER_ID_ATTRIBUTE, userId);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove(MDC_USER_ID);
            RequestContext.clear();
        }
    }

    private static final UrlPathHelper PATH_HELPER = UrlPathHelper.defaultInstance;
    private static final Set<String> OPEN_EXACT =
            Set.of("/actuator", "/metrics", "/dashboard", "/dashboard.html", "/healthz");

    private static boolean isOpenPath(HttpServletRequest request) {
        // Context path stripped + URL-decoded + ";" params removed (UrlPathHelper),
        // then dot-segments collapsed (StringUtils.cleanPath). Deterministic across
        // Tomcat and MockMvc, and a "/actuator/.." traversal resolves out → NOT open.
        String path = StringUtils.cleanPath(PATH_HELPER.getPathWithinApplication(request));
        return OPEN_EXACT.contains(path) || path.startsWith("/actuator/");
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
        // Explicit snake_case keys so the 401 body shape (shared with TASK-06's
        // ErrorResponse) is fixed regardless of the app's Jackson naming strategy.
        LinkedHashMap<String, String> body = new LinkedHashMap<>();
        body.put("error", "unauthorized");
        body.put("message", "missing or invalid bearer token");
        body.put("correlation_id", correlationId);
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
