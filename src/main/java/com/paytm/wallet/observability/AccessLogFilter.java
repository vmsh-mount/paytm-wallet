package com.paytm.wallet.observability;

import com.paytm.wallet.config.AuthFilter;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;

/**
 * One structured line per request: method, path, status, {@code duration_ms}, and
 * the resolved {@code user_id} (from the request attribute {@link AuthFilter} sets,
 * which outlives the MDC). Sits just inside {@link CorrelationIdFilter} and
 * outside {@link AuthFilter}, so it logs {@code 401}s too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class AccessLogFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger("com.paytm.wallet.access");
    private static final UrlPathHelper PATH = UrlPathHelper.defaultInstance;

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        long startNanos = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            var line = log.atInfo()
                    .addKeyValue("event", "http.access")
                    .addKeyValue("method", request.getMethod())
                    .addKeyValue("path", PATH.getPathWithinApplication(request))
                    .addKeyValue("status", response.getStatus())
                    .addKeyValue("duration_ms", (System.nanoTime() - startNanos) / 1_000_000);
            Object userId = request.getAttribute(AuthFilter.USER_ID_ATTRIBUTE);
            if (userId != null) {
                line = line.addKeyValue("user_id", userId);
            }
            line.log("request");
        }
    }
}
