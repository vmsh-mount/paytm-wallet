package com.paytm.wallet.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.paytm.wallet.service.WalletService;
import com.paytm.wallet.support.FakeWalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Standalone MockMvc — controller + validation + {@link ApiExceptionHandler}, no Spring context. */
class WalletControllerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        WalletService service = new WalletService(new FakeWalletRepository());
        ObjectMapper mapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mvc = MockMvcBuilders.standaloneSetup(new WalletController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    @Test
    void post_creates_wallet_with_zero_balance_and_is_idempotent() throws Exception {
        String body = mvc.perform(post("/wallets").contentType("application/json")
                        .content("{\"user_id\":\"alice\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.balance_paise").value(0))
                .andReturn().getResponse().getContentAsString();

        mvc.perform(post("/wallets").contentType("application/json").content("{\"user_id\":\"alice\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(new ObjectMapper().readTree(body).get("id").asText()));
    }

    @Test
    void blank_user_id_is_400() throws Exception {
        mvc.perform(post("/wallets").contentType("application/json").content("{\"user_id\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    void unknown_wallet_is_404() throws Exception {
        mvc.perform(get("/wallets/{id}", "11111111-1111-1111-1111-111111111111"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void non_uuid_path_is_400() throws Exception {
        mvc.perform(get("/wallets/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest());
    }
}
