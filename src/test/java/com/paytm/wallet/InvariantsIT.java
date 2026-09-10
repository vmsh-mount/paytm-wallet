package com.paytm.wallet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paytm.wallet.support.AbstractPostgresIT;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The four graded invariants, end-to-end against a real Postgres. Filled in by
 * the task that implements each invariant; #4 (race-free get-or-create) lands
 * here with TASK-03.
 */
@AutoConfigureMockMvc
class InvariantsIT extends AbstractPostgresIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    MockMvc mvc;

    private String createWallet(String userId) throws Exception {
        String body = mvc.perform(post("/wallets")
                        .header("Authorization", "Bearer dev-token-alice")
                        .contentType("application/json")
                        .content("{\"user_id\":\"" + userId + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("id").asText();
    }

    @Test
    void get_or_create_is_idempotent() throws Exception {
        String user = "idem-" + System.nanoTime();
        Set<String> ids = IntStream.range(0, 5).mapToObj(i -> {
            try {
                return createWallet(user);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).collect(Collectors.toSet());

        assertThat(ids).hasSize(1);
        assertThat(count(user)).isEqualTo(1);
    }

    @Test
    void concurrent_get_or_create_yields_one_wallet() throws Exception {
        int n = 50;
        String user = "race-" + System.nanoTime();
        var barrier = new CyclicBarrier(n);
        var ids = new CopyOnWriteArrayList<String>();
        var statuses = new CopyOnWriteArrayList<Integer>();

        try (var pool = Executors.newFixedThreadPool(n)) {
            var futures = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    var res = mvc.perform(post("/wallets")
                            .header("Authorization", "Bearer dev-token-alice")
                            .contentType("application/json")
                            .content("{\"user_id\":\"" + user + "\"}")).andReturn().getResponse();
                    statuses.add(res.getStatus());
                    if (res.getStatus() == 200) {
                        ids.add(JSON.readTree(res.getContentAsString()).get("id").asText());
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get(20, TimeUnit.SECONDS);
            }
        }

        assertThat(statuses).as("no 5xx").allMatch(s -> s == 200);
        assertThat(ids).hasSize(n);
        assertThat(Set.copyOf(ids)).as("exactly one wallet id across all responses").hasSize(1);
        assertThat(count(user)).as("exactly one row").isEqualTo(1);
        assertThat(balanceOf(ids.get(0))).isZero();
    }

    private long count(String userId) {
        return jdbc.queryForObject("SELECT count(*) FROM wallets WHERE user_id = ?", Long.class, userId);
    }

    private long balanceOf(String walletId) {
        return jdbc.queryForObject(
                "SELECT balance_paise FROM wallets WHERE id = ?::uuid", Long.class, walletId);
    }

    @Test
    @Disabled("TASK-04")
    void conservation_holds_under_concurrent_transfers() {
    }

    @Test
    @Disabled("TASK-04")
    void no_overdraft_under_contention() {
    }

    @Test
    @Disabled("TASK-05")
    void same_idempotency_key_applies_once() {
    }

    @Test
    @Disabled("TASK-05")
    void same_key_different_body_is_409() {
    }
}
