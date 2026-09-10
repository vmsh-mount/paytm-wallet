package com.paytm.wallet.config;

import com.paytm.wallet.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AuthIT extends AbstractPostgresIT {

    @Autowired
    MockMvc mvc;

    @Test
    void post_wallets_without_token_is_401_json() throws Exception {
        mvc.perform(post("/wallets").contentType("application/json").content("{\"user_id\":\"alice\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"))
                .andExpect(jsonPath("$.correlation_id").isNotEmpty())
                .andExpect(header().exists("X-Correlation-Id"));
    }

    @Test
    void post_wallets_with_unknown_token_is_401() throws Exception {
        mvc.perform(post("/wallets").header("Authorization", "Bearer not-a-real-token")
                        .contentType("application/json").content("{\"user_id\":\"alice\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void actuator_health_is_open() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void valid_token_passes_auth() throws Exception {
        // Controller body is still scaffold (throws) — the point is only that we get
        // past the filter, i.e. the response is NOT 401.
        mvc.perform(post("/wallets").header("Authorization", "Bearer dev-token-alice")
                        .contentType("application/json").content("{\"user_id\":\"alice\"}"))
                .andExpect(result -> org.assertj.core.api.Assertions
                        .assertThat(result.getResponse().getStatus()).isNotEqualTo(401));
    }
}
