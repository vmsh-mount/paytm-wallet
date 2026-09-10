package com.paytm.wallet.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paytm.wallet.support.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** The domain-event stream for a completed / declined / replayed transfer. */
@AutoConfigureMockMvc
class LoggingIT extends AbstractPostgresIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    MockMvc mvc;

    private ListAppender<ILoggingEvent> events;

    @BeforeEach
    void capture() {
        events = new ListAppender<>();
        events.start();
        ((Logger) LoggerFactory.getLogger("com.paytm.wallet.events")).addAppender(events);
    }

    @AfterEach
    void release() {
        ((Logger) LoggerFactory.getLogger("com.paytm.wallet.events")).detachAppender(events);
    }

    private List<String> eventCodes() {
        return events.list.stream()
                .map(e -> String.valueOf(e.getKeyValuePairs().stream()
                        .filter(p -> p.key.equals("event")).findFirst().map(p -> p.value).orElse(null)))
                .toList();
    }

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

    private MockHttpServletRequestBuilder transfer(UUID from, UUID to, long amount, String key) {
        return post("/transfers").header("Authorization", "Bearer dev-token-alice")
                .contentType("application/json")
                .content("{\"from\":\"" + from + "\",\"to\":\"" + to + "\",\"amount_paise\":" + amount
                        + ",\"idempotency_key\":\"" + key + "\"}");
    }

    @Test
    void completed_transfer_emits_received_debited_credited_completed_in_order() throws Exception {
        UUID a = wallet("alice", "dev-token-alice", 100_000);
        UUID b = wallet("bob", "dev-token-bob", 0);
        events.list.clear();

        mvc.perform(transfer(a, b, 30_000, "log-" + UUID.randomUUID()));

        assertThat(eventCodes()).containsSubsequence(
                "transfer.received", "transfer.debited", "transfer.credited", "transfer.completed");
        // one shared correlation id across the whole sequence
        assertThat(events.list.stream().map(e -> e.getMDCPropertyMap().get("correlation_id")).distinct())
                .hasSize(1);
    }

    @Test
    void declined_transfer_emits_received_then_declined() throws Exception {
        UUID a = wallet("alice", "dev-token-alice", 100);
        UUID b = wallet("bob", "dev-token-bob", 0);
        events.list.clear();

        mvc.perform(transfer(a, b, 5_000, "log-" + UUID.randomUUID()));

        assertThat(eventCodes()).containsSubsequence("transfer.received", "transfer.declined");
        assertThat(eventCodes()).doesNotContain("transfer.completed", "transfer.debited");
    }

    @Test
    void retry_storm_emits_one_completed_and_the_rest_replays() throws Exception {
        UUID a = wallet("alice", "dev-token-alice", 1_000_000);
        UUID b = wallet("bob", "dev-token-bob", 0);
        String key = "storm-" + UUID.randomUUID();
        events.list.clear();

        for (int i = 0; i < 4; i++) {
            mvc.perform(transfer(a, b, 4_000, key));
        }

        List<String> codes = eventCodes();
        assertThat(codes.stream().filter("transfer.completed"::equals).count()).isEqualTo(1);
        assertThat(codes.stream().filter("transfer.idempotent_replay"::equals).count()).isEqualTo(3);
    }

    @Test
    void a_clean_run_logs_no_errors_and_no_raw_keys() throws Exception {
        UUID a = wallet("alice", "dev-token-alice", 100_000);
        UUID b = wallet("bob", "dev-token-bob", 0);
        String key = "secret-key-" + UUID.randomUUID();
        events.list.clear();

        mvc.perform(transfer(a, b, 1_000, key));

        assertThat(events.list).noneMatch(e -> e.getLevel().toString().equals("ERROR"));
        assertThat(events.list).noneMatch(e -> e.toString().contains(key));
    }
}
