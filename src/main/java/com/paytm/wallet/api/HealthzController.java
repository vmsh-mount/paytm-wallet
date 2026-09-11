package com.paytm.wallet.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A trivial, dependency-free {@code 200} — no DB round trip. Unauthenticated
 * (allowlisted in {@code AuthFilter}). Exists purely so a reviewer (or a
 * keep-warm ping) can wake a sleeping free-tier instance without needing a
 * bearer token or caring whether Postgres is reachable; {@code
 * /actuator/health/readiness} is still the real readiness signal.
 */
@RestController
public class HealthzController {

    @GetMapping("/healthz")
    public String healthz() {
        return "OK";
    }
}
