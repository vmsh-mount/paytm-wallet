package com.paytm.wallet.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Minimal bearer-token auth. A static map of {@code token -> userId} (from
 * {@code wallet.auth.tokens} config) identifies the caller and stores the
 * user id as a request attribute. Auth sophistication is explicitly NOT graded;
 * this is just enough to attribute a call to a user.
 *
 * Unauthenticated paths: /actuator/**, /healthz.
 *
 * TODO(scaffold): implement.
 */
@Component
@Order(1)
public class AuthFilter implements Filter {

    public static final String CALLER_USER_ID = "callerUserId";

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        // TODO: parse Authorization: Bearer <token>, resolve user, 401 on failure.
        HttpServletRequest http = (HttpServletRequest) req;
        chain.doFilter(req, res);
    }
}
