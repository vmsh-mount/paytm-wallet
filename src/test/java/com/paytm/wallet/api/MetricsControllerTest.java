package com.paytm.wallet.api;

import io.micrometer.core.instrument.Counter;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MetricsControllerTest {

    @Test
    void metrics_endpoint_is_prometheus_text_with_domain_meters() throws Exception {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        Counter.builder("wallet.transfers.completed").register(registry).increment(3);
        Counter.builder("wallet.transfers.declined").tag("reason", "insufficient_funds")
                .register(registry).increment();

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MetricsController(registry)).build();

        String body = mvc.perform(get("/metrics"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("wallet_transfers_completed_total")
                .contains("wallet_transfers_declined_total{reason=\"insufficient_funds\"}")
                .containsPattern("wallet_transfers_completed_total\\s+3(\\.0)?");
    }
}
