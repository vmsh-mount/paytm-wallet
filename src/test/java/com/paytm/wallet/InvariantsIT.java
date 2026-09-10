package com.paytm.wallet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paytm.wallet.domain.Transfer;
import com.paytm.wallet.service.TransferService;
import com.paytm.wallet.service.transfer.TransferRequest;
import com.paytm.wallet.support.AbstractPostgresIT;
import com.paytm.wallet.support.TestSeed;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The four graded invariants, end-to-end against a real Postgres. #4 landed with
 * TASK-03; #1 (conservation) and #2 (no overdraft) land here (TASK-04) against
 * the conditional-update engine.
 */
@AutoConfigureMockMvc
class InvariantsIT extends AbstractPostgresIT {

    private static final Logger log = LoggerFactory.getLogger(InvariantsIT.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    MockMvc mvc;

    @Autowired
    TransferService transferService;

    private final Map<UUID, String> owner = new HashMap<>();

    private UUID seedWallet(String user, long balancePaise) {
        String uniqueUser = user + "-" + UUID.randomUUID();
        UUID id = TestSeed.wallet(jdbc, uniqueUser, balancePaise);
        owner.put(id, uniqueUser);
        return id;
    }

    private Transfer transfer(UUID from, UUID to, long amount) {
        return transferService.create(
                new TransferRequest(from, to, amount, UUID.randomUUID().toString()),
                owner.get(from), "it-cid");
    }

    private long balance(UUID walletId) {
        return jdbc.queryForObject("SELECT balance_paise FROM wallets WHERE id = ?", Long.class, walletId);
    }

    private long sumBalances() {
        return jdbc.queryForObject("SELECT COALESCE(SUM(balance_paise), 0) FROM wallets", Long.class);
    }

    private long minBalance() {
        return jdbc.queryForObject("SELECT COALESCE(MIN(balance_paise), 0) FROM wallets", Long.class);
    }

    private long count(String status) {
        return jdbc.queryForObject("SELECT count(*) FROM transfers WHERE status = ?", Long.class, status);
    }

    // ---- #4 race-free get-or-create (TASK-03) -------------------------------

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
        assertThat(Set.copyOf(ids)).as("exactly one wallet id").hasSize(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM wallets WHERE user_id = ?", Long.class, user)).isEqualTo(1);
    }

    @Test
    void get_or_create_is_idempotent() throws Exception {
        String user = "idem-" + System.nanoTime();
        Set<String> ids = new java.util.HashSet<>();
        for (int i = 0; i < 5; i++) {
            ids.add(createWallet(user));
        }
        assertThat(ids).hasSize(1);
    }

    // ---- #1 conservation + #2 no overdraft (TASK-04) ----------------------

    @Test
    void basic_transfer_moves_money_and_completes() {
        UUID a = seedWallet("A", 100);
        UUID b = seedWallet("B", 0);

        Transfer t = transfer(a, b, 30);

        assertThat(t.status()).isEqualTo(Transfer.Status.COMPLETED);
        assertThat(balance(a)).isEqualTo(70);
        assertThat(balance(b)).isEqualTo(30);
    }

    @Test
    void insufficient_funds_declines_atomically() {
        UUID a = seedWallet("A", 20);
        UUID b = seedWallet("B", 50);

        Transfer t = transfer(a, b, 100);

        assertThat(t.status()).isEqualTo(Transfer.Status.DECLINED);
        assertThat(t.declineReason()).isEqualTo("insufficient_funds");
        assertThat(balance(a)).isEqualTo(20);   // byte-identical to before
        assertThat(balance(b)).isEqualTo(50);
        assertThat(count("COMPLETED")).isZero();
    }

    @Test
    void conservation_holds_under_concurrent_transfers() throws Exception {
        long each = 100_000L;
        UUID a = seedWallet("A", each), b = seedWallet("B", each), c = seedWallet("C", each);
        long total = 3 * each;
        UUID[][] pairs = {{a, b}, {b, a}, {b, c}, {c, a}, {a, c}, {c, b}};

        int rounds = 200;
        var errors = new CopyOnWriteArrayList<Throwable>();
        var latch = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(48)) {
            var futures = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < rounds; i++) {
                UUID[] p = pairs[i % pairs.length];
                long amount = ThreadLocalRandom.current().nextLong(1, 1_000);
                futures.add(pool.submit(() -> {
                    latch.await(10, TimeUnit.SECONDS);
                    try {
                        transfer(p[0], p[1], amount);
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                    return null;
                }));
            }
            latch.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        }

        assertThat(errors).as("no deadlock / no unexpected errors").isEmpty();
        assertThat(sumBalances()).as("Σ balances unchanged").isEqualTo(total);
        assertThat(minBalance()).as("no negative balance").isGreaterThanOrEqualTo(0);
        log.info("conservation run: {} completed, {} declined",
                count("COMPLETED"), count("DECLINED"));
    }

    @Test
    void no_overdraft_under_contention() throws Exception {
        UUID a = seedWallet("A", 500);
        UUID b = seedWallet("B", 0);
        int attempts = 100;
        long amount = 100; // only 5 can succeed

        var errors = new CopyOnWriteArrayList<Throwable>();
        var latch = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(attempts)) {
            var futures = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < attempts; i++) {
                futures.add(pool.submit(() -> {
                    latch.await(10, TimeUnit.SECONDS);
                    try {
                        transfer(a, b, amount);
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                    return null;
                }));
            }
            latch.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        }

        assertThat(errors).isEmpty();
        assertThat(balance(a)).isEqualTo(0);
        assertThat(balance(b)).isEqualTo(500);
        assertThat(minBalance()).isGreaterThanOrEqualTo(0);
        assertThat(count("COMPLETED")).isEqualTo(5);
        assertThat(count("DECLINED")).isEqualTo(attempts - 5);
    }

    @Test
    void reverse_transfers_do_not_deadlock() throws Exception {
        UUID a = seedWallet("A", 1_000_000L);
        UUID b = seedWallet("B", 1_000_000L);
        long total = 2_000_000L;
        int rounds = 200;

        var errors = new CopyOnWriteArrayList<Throwable>();
        var latch = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(32)) {
            var futures = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < rounds; i++) {
                boolean forward = (i % 2 == 0);
                long amount = ThreadLocalRandom.current().nextLong(1, 50);
                futures.add(pool.submit(() -> {
                    latch.await(10, TimeUnit.SECONDS);
                    try {
                        transfer(forward ? a : b, forward ? b : a, amount);
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                    return null;
                }));
            }
            latch.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        }

        assertThat(errors).as("zero 40P01 deadlock errors").isEmpty();
        assertThat(sumBalances()).isEqualTo(total);
    }

    @Test
    void many_sequential_transfers_conserve() {
        UUID a = seedWallet("A", 500_000L);
        UUID b = seedWallet("B", 500_000L);
        long total = 1_000_000L;

        long startNanos = System.nanoTime();
        int n = 500;
        for (int i = 0; i < n; i++) {
            transfer(i % 2 == 0 ? a : b, i % 2 == 0 ? b : a, 1 + (i % 7));
        }
        long millis = (System.nanoTime() - startNanos) / 1_000_000;

        assertThat(sumBalances()).isEqualTo(total);
        assertThat(minBalance()).isGreaterThanOrEqualTo(0);
        log.info("{} sequential transfers in {} ms ({} ms/op)", n, millis, (double) millis / n);
    }

    // ---- #3 exactly-once (TASK-05) --------------------------------------

    @Test
    @Disabled("TASK-05")
    void same_idempotency_key_applies_once() {
    }

    @Test
    @Disabled("TASK-05")
    void same_key_different_body_is_409() {
    }
}
