package com.paytm.wallet.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.paytm.wallet.config.AuthFilter;
import com.paytm.wallet.config.AuthProperties;
import com.paytm.wallet.domain.Transfer;
import com.paytm.wallet.observability.CorrelationIdFilter;
import com.paytm.wallet.service.DomainExceptions;
import com.paytm.wallet.service.TransferService;
import com.paytm.wallet.service.transfer.TransferEngine;
import com.paytm.wallet.service.transfer.TransferOutcome;
import com.paytm.wallet.support.FakeTransferRepository;
import com.paytm.wallet.support.FakeWalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Standalone MockMvc — the full status-code matrix, with the real auth + correlation filters. */
class TransferControllerTest {

    private MockMvc mvc;
    private final FakeWalletRepository wallets = new FakeWalletRepository();
    private final FakeTransferRepository transfers = new FakeTransferRepository();
    private final com.paytm.wallet.support.FakeTransferEngine engine =
            new com.paytm.wallet.support.FakeTransferEngine();
    private final AtomicReference<TransferOutcome> engineResult = engine.result;
    private final AtomicReference<RuntimeException> engineThrows = engine.error;

    private UUID aliceWallet;
    private UUID bobWallet;

    @BeforeEach
    void setUp() {
        wallets.insertIfAbsent("alice");
        wallets.insertIfAbsent("bob");
        aliceWallet = wallets.findByUserId("alice").orElseThrow().id();
        bobWallet = wallets.findByUserId("bob").orElseThrow().id();

        TransferService service = new TransferService(engine, transfers, wallets,
                new com.paytm.wallet.observability.WalletMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        ObjectMapper mapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
        AuthProperties auth = new AuthProperties();
        auth.setTokens("t-alice:alice,t-bob:bob");

        mvc = MockMvcBuilders.standaloneSetup(new TransferController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .addFilters(new CorrelationIdFilter(), new AuthFilter(auth, mapper))
                .build();
    }

    private Transfer canned(Transfer.Status status, String reason) {
        return new Transfer(UUID.randomUUID(), aliceWallet, bobWallet, 30, "k", "fp", status, reason, Instant.now());
    }

    private String body(UUID from, UUID to, long amount, String key) {
        return "{\"from\":\"" + from + "\",\"to\":\"" + to + "\",\"amount_paise\":" + amount
                + ",\"idempotency_key\":\"" + key + "\"}";
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postTransfer(
            String token, String json) {
        return post("/transfers").header("Authorization", "Bearer " + token)
                .contentType("application/json").content(json);
    }

    @Test
    void fresh_completed_is_201() throws Exception {
        engineResult.set(TransferOutcome.fresh(canned(Transfer.Status.COMPLETED, null)));
        mvc.perform(postTransfer("t-alice", body(aliceWallet, bobWallet, 30, "k1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.decline_reason").doesNotExist());
    }

    @Test
    void fresh_declined_is_also_201() throws Exception {
        engineResult.set(TransferOutcome.fresh(canned(Transfer.Status.DECLINED, "insufficient_funds")));
        mvc.perform(postTransfer("t-alice", body(aliceWallet, bobWallet, 30, "k1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.decline_reason").value("insufficient_funds"));
    }

    @Test
    void idempotent_replay_is_200() throws Exception {
        engineResult.set(TransferOutcome.replay(canned(Transfer.Status.COMPLETED, null)));
        mvc.perform(postTransfer("t-alice", body(aliceWallet, bobWallet, 30, "k1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void same_key_different_body_is_409() throws Exception {
        engineThrows.set(new DomainExceptions.IdempotencyConflict("key reused"));
        mvc.perform(postTransfer("t-alice", body(aliceWallet, bobWallet, 30, "k1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("idempotency_conflict"))
                .andExpect(jsonPath("$.correlation_id").isNotEmpty())
                .andExpect(header().exists("X-Correlation-Id"));
    }

    @Test
    void validation_failures_are_400() throws Exception {
        String[] bad = {
                "{\"to\":\"" + bobWallet + "\",\"amount_paise\":30,\"idempotency_key\":\"k\"}", // no from
                body(aliceWallet, bobWallet, 0, "k"),                                            // amount 0
                body(aliceWallet, bobWallet, 30, ""),                                            // blank key
                body(aliceWallet, bobWallet, 30, "x".repeat(201)),                               // key too long
        };
        for (String json : bad) {
            mvc.perform(postTransfer("t-alice", json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("bad_request"));
        }
    }

    @Test
    void caller_not_owner_is_403() throws Exception {
        mvc.perform(postTransfer("t-bob", body(aliceWallet, bobWallet, 30, "k1"))) // bob using alice's wallet
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    @Test
    void unknown_wallet_is_404() throws Exception {
        mvc.perform(postTransfer("t-alice", body(UUID.randomUUID(), bobWallet, 30, "k1")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void get_returns_transfer_and_404_for_unknown() throws Exception {
        Transfer stored = canned(Transfer.Status.DECLINED, "insufficient_funds");
        transfers.byId.put(stored.id(), stored);

        mvc.perform(get("/transfers/{id}", stored.id()).header("Authorization", "Bearer t-alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(stored.id().toString()))
                .andExpect(jsonPath("$.status").value("DECLINED"));

        mvc.perform(get("/transfers/{id}", UUID.randomUUID()).header("Authorization", "Bearer t-alice"))
                .andExpect(status().isNotFound());
    }
}
