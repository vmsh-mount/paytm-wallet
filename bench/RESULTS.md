# Engine benchmark results

Fixed-duration load, mixed transfers among a small wallet set. Re-run: `./bench/run.sh`
(needs a reachable Postgres; `DATABASE_URL` defaults to local). Each run appends below.

## Read (latest run: commit `0e1499b`, 16 threads / 8 wallets / 10 s, local Postgres)

As hypothesised in TASK-07, and unchanged by TASK-08's `RETURNING`-based audit reads:

- **conditional-update ≈ select-for-update** on throughput (7.8k vs 7.2k ops/s) — both hold
  two row locks for the same short window; the difference is noise at this scale.
- **serializable is ~2.3× slower** (3.4k ops/s). Under 16-way contention on 8 wallets it does
  a few thousand retries per 10 s, still leaks ~10–15 `SerializationExhausted` → `503`
  (retry budget 20), and its p99 blows out (tens of ms) — retried transactions wait through
  exponential backoff. Numbers vary run to run; the shape does not.
- **All three conserved Σ and never went negative.** Correctness is not the differentiator;
  cost and tail latency under contention are.

Conclusion: ship `conditional-update` — shortest critical section (one statement does
check + debit + returns the new balance), no retry machinery to reason about.



## Run 2026-09-10T16:05:51.283989Z

`THREADS=16 SECONDS=10 WALLETS=8` · `jdbc:postgresql://localhost:5432/wallet` · commit `0e1499b`

| engine | throughput/s | p50 ms | p99 ms | declined | errors | retries | conserved |
|--------|-------------:|-------:|-------:|---------:|-------:|--------:|:---------:|
| conditional-update |      7,784 |    1.0 |    14.4 |    0.0% |      0 |       0 | ✓ |
| select-for-update  |      7,435 |    1.0 |    11.9 |    0.0% |      0 |       0 | ✓ |
| serializable       |      3,443 |    0.3 |    66.1 |    0.0% |     12 |    3619 | ✓ |
