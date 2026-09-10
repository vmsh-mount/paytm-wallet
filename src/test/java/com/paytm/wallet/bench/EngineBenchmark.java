package com.paytm.wallet.bench;

import com.paytm.wallet.observability.WalletMetrics;
import com.paytm.wallet.service.transfer.ConditionalUpdateEngine;
import com.paytm.wallet.service.transfer.Engine;
import com.paytm.wallet.service.transfer.SelectForUpdateEngine;
import com.paytm.wallet.service.transfer.SerializableEngine;
import com.paytm.wallet.service.transfer.TransferEngine;
import com.paytm.wallet.service.transfer.TransferRequest;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;

/**
 * Fixed-duration load harness — {@code M} threads × mixed transfers for {@code T}
 * seconds against a real Postgres, once per engine. Identical schema / pool /
 * workload; only the engine changes. Appends a table to {@code bench/RESULTS.md}.
 *
 * <p>Run via {@code ./bench/run.sh} (needs a reachable Postgres — {@code DATABASE_URL},
 * default {@code jdbc:postgresql://localhost:5432/wallet}).
 */
public final class EngineBenchmark {

    static final int THREADS = env("BENCH_THREADS", 16);
    static final int SECONDS = env("BENCH_SECONDS", 10);
    static final int WALLETS = env("BENCH_WALLETS", 8);
    static final long START_BALANCE = 1_000_000_000L;

    public static void main(String[] args) throws Exception {
        // per-op debit/credit logging would drown the run; keep only warnings (retries, exhaustion)
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("com.paytm.wallet"))
                .setLevel(ch.qos.logback.classic.Level.WARN);

        String url = System.getenv().getOrDefault("DATABASE_URL", "jdbc:postgresql://localhost:5432/wallet");
        String user = System.getenv().getOrDefault("DATABASE_USER", "wallet");
        String pass = System.getenv().getOrDefault("DATABASE_PASSWORD", "wallet");

        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(url);
        hc.setUsername(user);
        hc.setPassword(pass);
        hc.setMaximumPoolSize(THREADS + 2);
        hc.setPoolName("bench-pool");
        HikariDataSource ds = new HikariDataSource(hc);
        Flyway.configure().dataSource(ds).load().migrate();

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        var registry = new SimpleMeterRegistry();
        var metrics = new WalletMetrics(registry);
        var txm = new DataSourceTransactionManager(ds);

        List<Result> results = new ArrayList<>();
        for (Engine engine : Engine.values()) {
            TransferEngine e = switch (engine) {
                case CONDITIONAL_UPDATE -> new ConditionalUpdateEngine(jdbc, txm, metrics);
                case SELECT_FOR_UPDATE -> new SelectForUpdateEngine(jdbc, txm, metrics);
                case SERIALIZABLE -> new SerializableEngine(jdbc, txm, metrics, 20);
            };
            results.add(run(engine, e, jdbc, registry, ds));
        }
        writeResults(results, url);
        results.forEach(r -> System.out.println(r.markdownRow()));
    }

    private static Result run(Engine engine, TransferEngine e, JdbcTemplate jdbc,
                              SimpleMeterRegistry registry, DataSource ds) throws Exception {
        jdbc.update("TRUNCATE transfers, wallets CASCADE");
        UUID[] w = new UUID[WALLETS];
        for (int i = 0; i < WALLETS; i++) {
            w[i] = jdbc.queryForObject(
                    "INSERT INTO wallets (user_id, balance_paise) VALUES (?, ?) RETURNING id",
                    UUID.class, engine + "-w" + i + "-" + UUID.randomUUID(), START_BALANCE);
        }
        double retriesBefore = retryCount(registry);

        var completed = new LongAdder();
        var declined = new LongAdder();
        var errors = new LongAdder();
        var latencies = new java.util.concurrent.ConcurrentLinkedQueue<Long>();
        long deadline = System.nanoTime() + SECONDS * 1_000_000_000L;
        var start = new CountDownLatch(1);

        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            Thread th = new Thread(() -> {
                await(start);
                var rnd = ThreadLocalRandom.current();
                // small warm-up before the clock matters
                while (System.nanoTime() < deadline) {
                    int fi = rnd.nextInt(WALLETS);
                    int ti = (fi + 1 + rnd.nextInt(WALLETS - 1)) % WALLETS;
                    var req = new TransferRequest(w[fi], w[ti], rnd.nextLong(1, 500),
                            UUID.randomUUID().toString());
                    long t0 = System.nanoTime();
                    try {
                        var out = e.execute(req, "bench");
                        latencies.add(System.nanoTime() - t0);
                        switch (out.transfer().status()) {
                            case COMPLETED -> completed.increment();
                            case DECLINED -> declined.increment();
                            default -> { }
                        }
                    } catch (Exception ex) {
                        errors.increment();
                    }
                }
            });
            threads.add(th);
            th.start();
        }
        start.countDown();
        for (Thread th : threads) {
            th.join();
        }

        long[] sorted = latencies.stream().mapToLong(Long::longValue).sorted().toArray();
        long ops = completed.sum() + declined.sum();
        return new Result(engine,
                ops / (double) SECONDS,
                pct(sorted, 0.50) / 1_000_000.0,
                pct(sorted, 0.99) / 1_000_000.0,
                declined.sum() * 100.0 / Math.max(ops, 1),
                errors.sum(),
                (long) (retryCount(registry) - retriesBefore),
                conserved(jdbc, w));
    }

    private static boolean conserved(JdbcTemplate jdbc, UUID[] w) {
        long total = jdbc.queryForObject("SELECT COALESCE(SUM(balance_paise),0) FROM wallets", Long.class);
        long min = jdbc.queryForObject("SELECT COALESCE(MIN(balance_paise),0) FROM wallets", Long.class);
        return total == (long) w.length * START_BALANCE && min >= 0;
    }

    private static double retryCount(SimpleMeterRegistry r) {
        var c = r.find("wallet.transfer.retries").counter();
        return c == null ? 0 : c.count();
    }

    private static long pct(long[] sorted, double p) {
        if (sorted.length == 0) return 0;
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(p * sorted.length) - 1)];
    }

    private static void await(CountDownLatch l) {
        try {
            l.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int env(String k, int dflt) {
        String v = System.getenv(k);
        return v == null ? dflt : Integer.parseInt(v);
    }

    record Result(Engine engine, double throughput, double p50ms, double p99ms,
                  double declineRate, long errors, long retries, boolean conserved) {
        String markdownRow() {
            return String.format("| %-18s | %,10.0f | %6.1f | %7.1f | %6.1f%% | %6d | %7d | %s |",
                    engine.configValue(), throughput, p50ms, p99ms, declineRate, errors, retries,
                    conserved ? "✓" : "**FAIL**");
        }
    }

    private static void writeResults(List<Result> results, String url) throws IOException {
        Path file = Path.of("bench/RESULTS.md");
        Files.createDirectories(file.getParent());
        StringBuilder sb = new StringBuilder();
        if (Files.exists(file)) {
            sb.append(Files.readString(file)).append("\n");
        } else {
            sb.append("# Engine benchmark results\n\n")
              .append("Fixed-duration load, mixed transfers among a small wallet set. ")
              .append("Re-run: `./bench/run.sh`.\n");
        }
        sb.append("\n## Run ").append(Instant.now()).append("\n\n")
          .append(String.format("`THREADS=%d SECONDS=%d WALLETS=%d` · `%s` · commit `%s`%n%n",
                  THREADS, SECONDS, WALLETS, url, gitShort()))
          .append("| engine | throughput/s | p50 ms | p99 ms | declined | errors | retries | conserved |\n")
          .append("|--------|-------------:|-------:|-------:|---------:|-------:|--------:|:---------:|\n");
        results.forEach(r -> sb.append(r.markdownRow()).append("\n"));
        Files.writeString(file, sb.toString());
        System.out.println("wrote " + file.toAbsolutePath());
    }

    private static String gitShort() {
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "--short", "HEAD").start();
            return new String(p.getInputStream().readAllBytes()).trim();
        } catch (IOException e) {
            return "unknown";
        }
    }

    private EngineBenchmark() {}
}
