# EVAL-C6 — Engine parity

| | |
|---|---|
| **Family** | Correctness |
| **Invariant** | #1 #2 #3 hold for every `TransferEngine` |
| **Owning task** | TASK-07 |
| **Runs against** | local compose (per engine) |
| **Status** | Ready — `EngineParityIT` (`@EnumSource(Engine.class)`: conservation, no-overdraft, deadlock-free, idempotent storm, 409) + `SerializableEngineTest`; benchmark in `bench/RESULTS.md`; live probe = `TRANSFER_ENGINE=<v> ./scripts/burst.sh` (TASK-12) |

## Goal

All three engines — `conditional-update`, `select-for-update`, `serializable` —
pass the same correctness probes. This is what makes "we rejected the heavier
alternatives" a measured claim, not an assertion.

## Preconditions

- Ability to restart the app with `TRANSFER_ENGINE` set to each value
  (compose override or `docker compose up -e`).

## Parameters

| Name | Default | Meaning |
|------|---------|---------|
| `ENGINES` | `conditional-update select-for-update serializable` | iterated |

## Procedure

For each `engine` in `ENGINES`:

1. `TRANSFER_ENGINE=<engine> docker compose up -d --wait`.
2. Run `EVAL-C2`, `EVAL-C3`, `EVAL-C4`, `EVAL-C5` against the instance.
3. Scrape `/metrics`; record `wallet_transfer_duration_seconds` p50/p99,
   `wallet_transfer_retries_total` (serializable only), decline count.
4. Record pass/fail per sub-scenario.

## Pass criteria

- [ ] `EVAL-C2`, `C3`, `C4`, `C5` **pass for all three engines**.
- [ ] `serializable`: `wallet_transfer_retries_total > 0` during `EVAL-C4`
      (it *should* hit serialization failures under contention) and still
      converges to the correct outcome.
- [ ] `serializable`: zero requests end in `500`; any exhausted-retry ends in
      `503`.
- [ ] No engine produces a `40P01` that escapes as a `5xx`.

## Fail signatures

- An engine fails a probe the others pass → that engine's implementation is
  wrong (not the invariant).
- `serializable` never retries under `EVAL-C4` → isolation level not actually set.
- Throughput/latency numbers missing → the tagged timer isn't wired per engine.

## Artifacts captured

- Per-engine results matrix, `/metrics` snapshot (latency, retries, declines),
  feeds directly into `bench/RESULTS.md` and `WRITEUP.md` §rejected-alternatives.
