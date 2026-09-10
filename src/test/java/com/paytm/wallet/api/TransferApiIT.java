package com.paytm.wallet.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paytm.wallet.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end walk of the transfer status-code contract against a real Postgres. */
@AutoConfigureMockMvc
class TransferApiIT extends AbstractPostgresIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    MockMvc mvc;

    private UUID wallet(String user, String token, long fundPaise) throws Exception {
        String bodyText = mvc.perform(post("/wallets").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"user_id\":\"" + user + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JSON.readTree(bodyText).get("id").asText());
        if (fundPaise > 0) {
            jdbc.update("UPDATE wallets SET balance_paise = ? WHERE id = ?", fundPaise, id);
        }
        return id;
    }

    private MockHttpServletRequestBuilder transfer(String token, UUID from, UUID to, long amount, String key) {
        return post("/transfers").header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"from\":\"" + from + "\",\"to\":\"" + to + "\",\"amount_paise\":" + amount
                        + ",\"idempotency_key\":\"" + key + "\"}");
    }

    @Test
    void full_status_code_walk() throws Exception {
        UUID a = wallet("alice", "dev-token-alice", 100_000);
        UUID b = wallet("bob", "dev-token-bob", 0);
        String key = "api-" + UUID.randomUUID();

        // fresh completed -> 201
        String created = mvc.perform(transfer("dev-token-alice", a, b, 30_000, key))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andReturn().getResponse().getContentAsString();
        String transferId = JSON.readTree(created).get("id").asText();

        // GET -> 200, same shape
        mvc.perform(get("/transfers/{id}", transferId).header("Authorization", "Bearer dev-token-alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.amount_paise").value(30_000));

        // replay (same key + body) -> 200, same transfer id
        mvc.perform(transfer("dev-token-alice", a, b, 30_000, key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(transferId));

        // same key, different amount -> 409
        mvc.perform(transfer("dev-token-alice", a, b, 31_000, key))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("idempotency_conflict"));

        // insufficient funds -> 201 DECLINED
        mvc.perform(transfer("dev-token-alice", a, b, 999_999_999L, "api-" + UUID.randomUUID()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.decline_reason").value("insufficient_funds"));

        // caller not owner -> 403
        mvc.perform(transfer("dev-token-bob", a, b, 10_000, "api-" + UUID.randomUUID()))
                .andExpect(status().isForbidden());

        // unknown transfer -> 404
        mvc.perform(get("/transfers/{id}", UUID.randomUUID()).header("Authorization", "Bearer dev-token-alice"))
                .andExpect(status().isNotFound());

        // balances: exactly one 30_000 debit
        assertThat(jdbc.queryForObject("SELECT balance_paise FROM wallets WHERE id = ?", Long.class, a))
                .isEqualTo(70_000);
        assertThat(jdbc.queryForObject("SELECT balance_paise FROM wallets WHERE id = ?", Long.class, b))
                .isEqualTo(30_000);
    }

    @Test
    void error_body_carries_the_response_correlation_id() throws Exception {
        var result = mvc.perform(get("/transfers/{id}", UUID.randomUUID())
                        .header("Authorization", "Bearer dev-token-alice"))
                .andExpect(status().isNotFound())
                .andReturn();
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("correlation_id").asText())
                .isEqualTo(result.getResponse().getHeader("X-Correlation-Id"));
    }
}
