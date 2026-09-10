package com.paytm.wallet.api;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.ModelAndView;

/**
 * {@code /metrics} — the brief's wording — serving the same Prometheus text as
 * {@code /actuator/prometheus} (kept for tooling). Unauthenticated (allowlisted
 * in {@code AuthFilter}). {@code /dashboard} forwards to the static one-pager.
 */
@RestController
public class MetricsController {

    private static final String PROMETHEUS_004 = "text/plain; version=0.0.4; charset=utf-8";

    private final PrometheusMeterRegistry registry;

    public MetricsController(PrometheusMeterRegistry registry) {
        this.registry = registry;
    }

    @GetMapping(value = "/metrics", produces = PROMETHEUS_004)
    public String metrics() {
        return registry.scrape();
    }

    @GetMapping("/dashboard")
    public ModelAndView dashboard() {
        return new ModelAndView("forward:/dashboard.html");
    }
}
