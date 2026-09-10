# TASK-07 — Alternative engines + benchmark

| | |
|---|---|
| **Status** | Done |
| **Branch** | `task/07-alternative-engines` |
| **Depends on** | TASK-04, TASK-06 |
| **Invariant(s)** | #1 #2 (re-proven for each engine) |
| **Est. effort** | ~4h |

## Context

The write-up must name the *simplest-correct* mechanism and the heavier
alternatives *rejected*. To make that concrete (and defensible in a live
review), implement all three behind `TransferEngine`, run the same invariant
suite against each, and benchmark them under contention.

## Goal

`SelectForUpdateEngine` and `SerializableEngine` implemented and passing the full
`InvariantsIT` matrix; a reproducible benchmark producing a comparison table in
the write-up.

## Scope

### In scope
- `SelectForUpdateEngine`: `SELECT ... FROM wallets WHERE id IN (?,?) ORDER BY id
  FOR UPDATE`, app-side balance check, two `UPDATE`s, transfer+key insert, commit
- `SerializableEngine`: `SET TRANSACTION ISOLATION LEVEL SERIALIZABLE`, plain
  read-modify-write, catch SQLState `40001`, retry with capped attempts
  (`wallet.transfer.serializable-max-retries`) + exponential backoff + jitter;
  surface `serialization_failure_exhausted` → `503`
- Parameterize `InvariantsIT` over `EnumSource(Engine.class)`
- `bench/` — a JMH or a simple fixed-duration load harness:
  driver fires M threads × mixed transfers for T seconds against a Testcontainers
  DB; records throughput, p50/p99 latency, decline rate, retry count (serializable),
  deadlock count
- `bench/RESULTS.md` auto-appended table + a short prose read
- Metrics: `wallet.transfer.retries` (serializable), tagged by engine

### Out of scope
- Production tuning of pool sizes / DB params
- Multi-node / read-replica engines

## Design notes / decisions

- **Expected outcome (hypothesis to confirm):** all three correct;
  conditional-update ≈ select-for-update on throughput, both beat serializable
  under contention because serializable pays retry cost as conflict rate rises;
  conditional-update has the shortest critical section (one statement does
  check+debit). If the data contradicts this, the write-up follows the data.
- **Fairness:** identical schema, pool, workload, warm-up; only the engine bean
  changes via `TRANSFER_ENGINE`.
- **Serializable retry policy** is itself a design choice to defend: bounded
  attempts prevent a retry storm from becoming a DoS; jitter prevents lockstep
  re-collision; exhaustion is a `503` (retry later) not a `500`.
- Keep all three in the shipped image — costs nothing, and lets the reviewer flip
  `TRANSFER_ENGINE` on the live URL and re-run `burst.sh`.

## Deliverables

- `service/transfer/SelectForUpdateEngine`, `SerializableEngine` (impl)
- `service/transfer/Engine` enum + parameterized `InvariantsIT`
- `bench/` harness + `bench/RESULTS.md`
- `docs/WRITEUP.md` §"heavier alternatives you rejected" — filled from real numbers
- `evals/scenarios/EVAL-C6-engine-parity.md` (all engines pass same probes)

## Acceptance criteria

- [ ] `InvariantsIT` green for **all three** engines (conservation, no-overdraft,
      idempotency, deadlock-free)
- [ ] `SerializableEngine` retries on injected write-skew and eventually succeeds;
      exhaustion path returns `503` not `500`
- [ ] `./bench/run.sh` produces `bench/RESULTS.md` with a filled table for all 3
- [ ] Switching `TRANSFER_ENGINE` needs no code change / rebuild
- [ ] Write-up comparison table cites the benchmark numbers + commit hash

## Test plan

### Unit
| Test | Asserts |
|------|---------|
| `SerializableEngineTest` | `40001` → retry; after N failures → `SerializationExhausted` |
| `SelectForUpdateEngineTest` | insufficient balance detected pre-update → DECLINED, no writes |

### Integration (`InvariantsIT`, parameterized)
| Test | Asserts | Engines |
|------|---------|---------|
| all TASK-04/05 cases | re-run unchanged | **all 3** via `@EnumSource` |
| `serializable_recovers_from_write_skew` | concurrent overdraw attempts, one wins, retries logged | serializable |

### Eval scenarios
| Scenario | Runs against | Pass criteria |
|----------|--------------|---------------|
| `EVAL-C6` | local | `burst.sh` invariant probes pass with each `TRANSFER_ENGINE` value |

## Definition of done

- [ ] All engines pass the full IT matrix
- [ ] `bench/RESULTS.md` committed; numbers reproducible
- [ ] `WRITEUP.md` §rejected-alternatives written from data, not hand-waving
- [ ] Branch squash-merged, status Done

## AI: directed vs decided

- **Directed**: implement-all-three-to-compare strategy, serializable retry
  policy shape, keep all engines shippable.
- **Decided**: benchmark harness (JMH vs bespoke), workload mix constants,
  backoff constants.
