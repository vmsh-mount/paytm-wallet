# Engine benchmark results

Fixed-duration load, mixed transfers among a small wallet set. Re-run: `./bench/run.sh`
(needs a reachable Postgres; `DATABASE_URL` defaults to local). Each run appends below.

## Read (run of 2026-09-10, commit `c45300f`, 16 threads / 8 wallets / 10 s)

As hypothesised in TASK-07:

- **conditional-update ≈ select-for-update** on throughput (7.9k vs 7.5k ops/s) — both
  hold two row locks for the same short window; conditional-update's single check+debit
  statement is a hair ahead and has a slightly worse p99 tail here (noise at this scale).
- **serializable is ~2.4× slower** (3.2k ops/s) and the only engine with errors: 8 wallets
  under 16-way contention drove **1405 retries** and 124 `SerializationExhausted` → `503`
  (retry budget 20). It sheds load rather than corrupting it.
- **All three conserved Σ and never went negative.** Correctness is not the differentiator;
  cost under contention is.

Conclusion: ship `conditional-update` (shortest critical section, no retry machinery to
reason about). The numbers, not intuition, back the write-up's "rejected alternatives".

## Run 2026-09-10T15:32:05.762562Z

`THREADS=16 SECONDS=10 WALLETS=8` · `jdbc:postgresql://localhost:5432/wallet` · commit `c45300f`

| engine | throughput/s | p50 ms | p99 ms | declined | errors | retries | conserved |
|--------|-------------:|-------:|-------:|---------:|-------:|--------:|:---------:|
| conditional-update |      7,910 |    1.0 |    14.2 |    0.0% |      0 |       0 | ✓ |
| select-for-update  |      7,499 |    1.0 |    12.0 |    0.0% |      0 |       0 | ✓ |
| serializable       |      3,166 |    0.3 |    19.4 |    0.0% |    124 |    1405 | ✓ |
