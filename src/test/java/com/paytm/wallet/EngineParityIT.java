package com.paytm.wallet;

import com.paytm.wallet.domain.Transfer;
import com.paytm.wallet.observability.WalletMetrics;
import com.paytm.wallet.repo.TransferRepository;
import com.paytm.wallet.repo.WalletRepository;
import com.paytm.wallet.service.TransferService;
import com.paytm.wallet.service.transfer.ConditionalUpdateEngine;
import com.paytm.wallet.service.transfer.Engine;
import com.paytm.wallet.service.transfer.SelectForUpdateEngine;
import com.paytm.wallet.service.transfer.SerializableEngine;
import com.paytm.wallet.service.transfer.TransferEngine;
import com.paytm.wallet.service.transfer.TransferRequest;
import com.paytm.wallet.support.AbstractPostgresIT;
import com.paytm.wallet.support.TestSeed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Invariants #1 / #2 / #3 re-proven for <b>each</b> {@link Engine}. Same schema,
 * pool, workload — only the engine bean changes. Serializable uses a smaller
 * thread pool (its story under heavy contention is the retry cost, measured in
 * {@code bench/RESULTS.md}, not a correctness failure).
 */
class EngineParityIT extends AbstractPostgresIT {

    private static final Logger log = LoggerFactory.getLogger(EngineParityIT.class);

    @Autowired PlatformTransactionManager txManager;
    @Autowired WalletMetrics metrics;
    @Autowired TransferRepository transferRepository;
    @Autowired WalletRepository walletRepository;

    private final Map<UUID, String> owner = new HashMap<>();

    private TransferService serviceFor(Engine engine) {
        TransferEngine e = switch (engine) {
            case CONDITIONAL_UPDATE -> new ConditionalUpdateEngine(jdbc, txManager, metrics);
            case SELECT_FOR_UPDATE -> new SelectForUpdateEngine(jdbc, txManager, metrics);
            case SERIALIZABLE -> new SerializableEngine(jdbc, txManager, metrics, 50);
        };
        return new TransferService(e, transferRepository, walletRepository);
    }

    private static int poolSize(Engine engine) {
        return engine == Engine.SERIALIZABLE ? 4 : 16;
    }

    private UUID seed(String user, long balancePaise) {
        String u = user + "-" + UUID.randomUUID();
        UUID id = TestSeed.wallet(jdbc, u, balancePaise);
        owner.put(id, u);
        return id;
    }

    private Transfer transfer(TransferService svc, UUID from, UUID to, long amount) {
        return svc.create(new TransferRequest(from, to, amount, UUID.randomUUID().toString()),
                owner.get(from), "cid").transfer();
    }

    private long balance(UUID id) {
        return jdbc.queryForObject("SELECT balance_paise FROM wallets WHERE id = ?", Long.class, id);
    }

    private long sum() {
        return jdbc.queryForObject("SELECT COALESCE(SUM(balance_paise),0) FROM wallets", Long.class);
    }

    private long min() {
        return jdbc.queryForObject("SELECT COALESCE(MIN(balance_paise),0) FROM wallets", Long.class);
    }

    private long completed(String key) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM transfers WHERE idempotency_key = ?", Long.class, key);
    }

    /** Fire {@code n} tasks through a pool, gate them on one latch, collect throwables. */
    private void burst(int n, int pool, Runnable task) throws Exception {
        var errors = new CopyOnWriteArrayList<Throwable>();
        var gate = new CountDownLatch(1);
        try (var exec = Executors.newFixedThreadPool(pool)) {
            var futures = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < n; i++) {
                futures.add(exec.submit(() -> {
                    gate.await(10, TimeUnit.SECONDS);
                    try {
                        task.run();
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                    return null;
                }));
            }
            gate.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        }
        assertThat(errors).as("no unexpected errors / deadlocks").isEmpty();
    }

    @ParameterizedTest
    @EnumSource(Engine.class)
    void basic_transfer_and_atomic_decline(Engine engine) {
        TransferService svc = serviceFor(engine);
        UUID a = seed("A", 100), b = seed("B", 0);

        assertThat(transfer(svc, a, b, 30).status()).isEqualTo(Transfer.Status.COMPLETED);
        assertThat(balance(a)).isEqualTo(70);
        assertThat(balance(b)).isEqualTo(30);

        Transfer declined = transfer(svc, a, b, 999);
        assertThat(declined.status()).isEqualTo(Transfer.Status.DECLINED);
        assertThat(declined.declineReason()).isEqualTo("insufficient_funds");
        assertThat(balance(a)).isEqualTo(70); // untouched
        assertThat(balance(b)).isEqualTo(30);
    }

    @ParameterizedTest
    @EnumSource(Engine.class)
    void conservation_under_contention(Engine engine) throws Exception {
        TransferService svc = serviceFor(engine);
        long each = 100_000L;
        UUID a = seed("A", each), b = seed("B", each), c = seed("C", each);
        UUID[][] pairs = {{a, b}, {b, a}, {b, c}, {c, a}, {a, c}, {c, b}};

        burst(90, poolSize(engine), () -> {
            UUID[] p = pairs[ThreadLocalRandom.current().nextInt(pairs.length)];
            transfer(svc, p[0], p[1], ThreadLocalRandom.current().nextLong(1, 1_000));
        });

        assertThat(sum()).as("Σ unchanged").isEqualTo(3 * each);
        assertThat(min()).as("no negative balance").isGreaterThanOrEqualTo(0);
    }

    @ParameterizedTest
    @EnumSource(Engine.class)
    void no_overdraft_under_contention(Engine engine) throws Exception {
        TransferService svc = serviceFor(engine);
        UUID a = seed("A", 500), b = seed("B", 0);

        burst(18, poolSize(engine), () -> transfer(svc, a, b, 100)); // only 5 affordable

        assertThat(balance(a)).isEqualTo(0);
        assertThat(balance(b)).isEqualTo(500);
        assertThat(min()).isGreaterThanOrEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers WHERE status='COMPLETED'", Long.class))
                .isEqualTo(5);
    }

    @ParameterizedTest
    @EnumSource(Engine.class)
    void reverse_transfers_do_not_deadlock(Engine engine) throws Exception {
        TransferService svc = serviceFor(engine);
        UUID a = seed("A", 1_000_000L), b = seed("B", 1_000_000L);

        var i = new java.util.concurrent.atomic.AtomicInteger();
        burst(60, poolSize(engine), () -> {
            boolean fwd = i.getAndIncrement() % 2 == 0;
            transfer(svc, fwd ? a : b, fwd ? b : a, ThreadLocalRandom.current().nextLong(1, 50));
        });

        assertThat(sum()).isEqualTo(2_000_000L);
    }

    @ParameterizedTest
    @EnumSource(Engine.class)
    void idempotent_key_applies_once_under_a_storm(Engine engine) throws Exception {
        TransferService svc = serviceFor(engine);
        UUID a = seed("A", 1_000_000L), b = seed("B", 0);
        String key = "storm-" + UUID.randomUUID();
        var results = new CopyOnWriteArrayList<Transfer>();

        burst(15, poolSize(engine), () ->
                results.add(svc.create(new TransferRequest(a, b, 400, key), owner.get(a), "cid").transfer()));

        assertThat(results.stream().map(Transfer::id).collect(Collectors.toSet())).hasSize(1);
        assertThat(completed(key)).isEqualTo(1);
        assertThat(balance(a)).isEqualTo(1_000_000L - 400);
        assertThat(balance(b)).isEqualTo(400);
    }

    @ParameterizedTest
    @EnumSource(Engine.class)
    void same_key_different_body_is_a_conflict(Engine engine) {
        TransferService svc = serviceFor(engine);
        UUID a = seed("A", 100), b = seed("B", 0);
        String key = "conf-" + UUID.randomUUID();

        svc.create(new TransferRequest(a, b, 30, key), owner.get(a), "cid");
        assertThatThrownBy(() -> svc.create(new TransferRequest(a, b, 31, key), owner.get(a), "cid"))
                .isInstanceOf(com.paytm.wallet.service.DomainExceptions.IdempotencyConflict.class);
        assertThat(balance(a)).isEqualTo(70);
    }

    @Test
    void serializable_recovers_from_write_skew() throws Exception {
        TransferService svc = serviceFor(Engine.SERIALIZABLE);
        UUID a = seed("A", 10_000L), b = seed("B", 0);

        // Many concurrent debits on one row: a naive read-modify-write would lose
        // updates; SSI aborts the losers with 40001 and the engine retries them.
        // That all 20 (all affordable: 10_000 / 100) commit with Σ intact IS the
        // proof the retry loop recovered every conflict.
        burst(15, 4, () -> transfer(svc, a, b, 100));

        assertThat(sum()).isEqualTo(10_000L);
        assertThat(min()).isGreaterThanOrEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers WHERE status='COMPLETED'", Long.class))
                .isEqualTo(15);
        assertThat(balance(a)).isEqualTo(10_000L - 15 * 100);
    }
}
