package com.paytm.wallet.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paytm.wallet.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Drive a known mix of transfers, then assert the scrape reflects it exactly. */
@AutoConfigureMockMvc
class MetricsIT extends AbstractPostgresIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    MockMvc mvc;

    private UUID wallet(String user, String token, long fund) throws Exception {
        String body = mvc.perform(post("/wallets").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"user_id\":\"" + user + "\"}"))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JSON.readTree(body).get("id").asText());
        if (fund > 0) {
            jdbc.update("UPDATE wallets SET balance_paise = ? WHERE id = ?", fund, id);
        }
        return id;
    }

    private void transfer(UUID from, UUID to, long amount, String key) throws Exception {
        mvc.perform(post("/transfers").header("Authorization", "Bearer dev-token-alice")
                .contentType("application/json")
                .content("{\"from\":\"" + from + "\",\"to\":\"" + to + "\",\"amount_paise\":" + amount
                        + ",\"idempotency_key\":\"" + key + "\"}"));
    }

    // the Spring context (and its MeterRegistry) is cached and shared with other ITs,
    // so assert on deltas, never absolute counter values
    private double scrapeValue(String metric) throws Exception {
        String scrape = mvc.perform(get("/metrics")).andReturn().getResponse().getContentAsString();
        Matcher m = Pattern.compile("(?m)^" + Pattern.quote(metric) + "(?:\\{[^}]*})?\\s+([-\\d.eE+]+)$")
                .matcher(scrape);
        double v = m.find() ? Double.parseDouble(m.group(1)) : Double.NaN;
        return Double.isNaN(v) ? 0 : v;
    }

    @Test
    void metrics_is_open_and_reflects_the_transfer_mix() throws Exception {
        UUID a = wallet("alice", "dev-token-alice", 1_000_000);
        UUID b = wallet("bob", "dev-token-bob", 0);

        double completed0 = scrapeValue("wallet_transfers_completed_total");
        double declined0 = scrapeValue("wallet_transfers_declined_total");
        double replay0 = scrapeValue("wallet_transfers_idempotent_replay_total");

        for (int i = 0; i < 10; i++) {                         // 10 completed
            transfer(a, b, 100, "m-c" + i + "-" + UUID.randomUUID());
        }
        for (int i = 0; i < 3; i++) {                          // 3 declined — a (alice's own wallet) can't afford it
            transfer(a, b, 10_000_000, "m-d" + i + "-" + UUID.randomUUID());
        }
        String replayKey = "m-replay-" + UUID.randomUUID();
        transfer(a, b, 100, replayKey);                        // 1 completed
        for (int i = 0; i < 5; i++) {                          // 5 replays
            transfer(a, b, 100, replayKey);
        }

        String scrape = mvc.perform(get("/metrics"))          // unauthenticated
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(scrapeValue("wallet_transfers_completed_total") - completed0).isEqualTo(11.0);
        assertThat(scrapeValue("wallet_transfers_declined_total") - declined0).isEqualTo(3.0);
        assertThat(scrapeValue("wallet_transfers_idempotent_replay_total") - replay0).isEqualTo(5.0);
        assertThat(scrape).contains("http_server_requests_seconds");
        assertThat(scrape).contains("wallet_transfer_duration_seconds");
    }

    @Test
    void metrics_endpoint_needs_no_auth_and_dashboard_is_open() throws Exception {
        mvc.perform(get("/metrics")).andExpect(status().isOk());
        mvc.perform(get("/dashboard")).andExpect(status().isOk());
        mvc.perform(get("/dashboard.html")).andExpect(status().isOk());
    }
}
