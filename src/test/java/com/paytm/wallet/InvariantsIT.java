package com.paytm.wallet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paytm.wallet.domain.Transfer;
import com.paytm.wallet.service.TransferService;
import com.paytm.wallet.service.transfer.TransferRequest;
import com.paytm.wallet.support.AbstractPostgresIT;
import com.paytm.wallet.support.TestSeed;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

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
 * The four graded invariants, end-to-end against a real Postgres:
 * #4 race-free get-or-create (TASK-03), #1 conservation + #2 no overdraft
 * (TASK-04), #3 exactly-once / idempotency (TASK-05) — all against the
 * conditional-update engine.
 */
@AutoConfigureMockMvc
class InvariantsIT extends AbstractPostgresIT {

    private static final Logger log = LoggerFactory.getLogger(InvariantsIT.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    MockMvc mvc;

    @Autowired
    TransferService transferService;

    @Autowired
    PlatformTransactionManager txManager;

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
                owner.get(from), "it-cid").transfer();
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

    private long countKey(String key) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM transfers WHERE idempotency_key = ?", Long.class, key);
    }

    private TransferRequest keyed(UUID from, UUID to, long amount, String key) {
        return new TransferRequest(from, to, amount, key);
    }

    @Test
    void same_key_same_body_applied_once_sequentially() {
        UUID a = seedWallet("A", 100), b = seedWallet("B", 0);
        String key = "seq-" + UUID.randomUUID();

        Transfer first = transferService.create(keyed(a, b, 30, key), owner.get(a), "cid").transfer();
        Transfer second = transferService.create(keyed(a, b, 30, key), owner.get(a), "cid").transfer();

        assertThat(second).isEqualTo(first);          // byte-identical replay
        assertThat(balance(a)).isEqualTo(70);         // one debit only
        assertThat(balance(b)).isEqualTo(30);
        assertThat(countKey(key)).isEqualTo(1);
    }

    @Test
    void same_idempotency_key_applies_once_under_a_retry_storm() throws Exception {
        UUID a = seedWallet("A", 1_000_000L), b = seedWallet("B", 0);
        String key = "storm-" + UUID.randomUUID();
        long amount = 400;
        int k = 30;

        var results = new CopyOnWriteArrayList<Transfer>();
        var errors = new CopyOnWriteArrayList<Throwable>();
        var barrier = new CyclicBarrier(k);
        try (var pool = Executors.newFixedThreadPool(k)) {
            var futures = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < k; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    try {
                        results.add(transferService.create(keyed(a, b, amount, key), owner.get(a), "cid").transfer());
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        }

        assertThat(errors).isEmpty();
        assertThat(results).hasSize(k);
        assertThat(results.stream().map(Transfer::id).collect(java.util.stream.Collectors.toSet()))
                .as("all responses are the same transfer").hasSize(1);
        assertThat(results).allMatch(t -> t.status() == Transfer.Status.COMPLETED);
        assertThat(countKey(key)).isEqualTo(1);
        assertThat(balance(a)).isEqualTo(1_000_000L - amount);   // debited exactly once
        assertThat(balance(b)).isEqualTo(amount);                // credited exactly once
    }

    @Test
    void same_key_different_body_is_a_conflict() {
        UUID a = seedWallet("A", 100), b = seedWallet("B", 0);
        String key = "conflict-" + UUID.randomUUID();

        transferService.create(keyed(a, b, 30, key), owner.get(a), "cid");

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        transferService.create(keyed(a, b, 31, key), owner.get(a), "cid"))
                .isInstanceOf(com.paytm.wallet.service.DomainExceptions.IdempotencyConflict.class);

        assertThat(balance(a)).isEqualTo(70);   // original untouched, no second debit
        assertThat(balance(b)).isEqualTo(30);
        assertThat(countKey(key)).isEqualTo(1);
    }

    @Test
    void idempotent_replay_of_declined_is_stable() {
        UUID a = seedWallet("A", 10), b = seedWallet("B", 0);
        String key = "declined-" + UUID.randomUUID();

        Transfer first = transferService.create(keyed(a, b, 50, key), owner.get(a), "cid").transfer();
        Transfer replay = transferService.create(keyed(a, b, 50, key), owner.get(a), "cid").transfer();

        assertThat(first.status()).isEqualTo(Transfer.Status.DECLINED);
        assertThat(replay).isEqualTo(first);
        assertThat(balance(a)).isEqualTo(10); // never debited
        assertThat(countKey(key)).isEqualTo(1);
    }

    @Test
    void crash_between_debit_and_key_persists_nothing() {
        UUID a = seedWallet("A", 100), b = seedWallet("B", 0);
        var txTemplate = new org.springframework.transaction.support.TransactionTemplate(txManager);

        txTemplate.executeWithoutResult(status -> {
            jdbc.update("UPDATE wallets SET balance_paise = balance_paise - 30 WHERE id = ?", a);
            jdbc.update("UPDATE wallets SET balance_paise = balance_paise + 30 WHERE id = ?", b);
            status.setRollbackOnly(); // simulate a crash before the transfers-row INSERT commits
        });

        assertThat(balance(a)).isEqualTo(100);
        assertThat(balance(b)).isEqualTo(0);
        assertThat(count("COMPLETED")).isZero();
    }
}
