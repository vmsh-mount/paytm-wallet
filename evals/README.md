# Evals

The invariants in the brief are **properties**, not features. Evals are how we
verify those properties hold — against a running instance, local or deployed,
as a black box.

## Two families

| Family | Prefix | Verifies | Run against |
|--------|--------|----------|-------------|
| **Correctness** | `EVAL-C*` | the four graded invariants under concurrency & failure | local compose + deployed URL |
| **Operational** | `EVAL-O*` | genuine deploy / containerize / observe | CI + deployed URL |

Each scenario is a markdown spec in [`scenarios/`](scenarios/) with a fixed shape
(see [`scenarios/TEMPLATE.md`](scenarios/TEMPLATE.md)): id, invariant, preconditions,
procedure, parameters, **pass criteria**, **fail signatures**, artifacts.

## How they relate to the rest of the repo

```
invariant  ──►  InvariantsIT (white-box, Testcontainers, all 3 engines)   ← fast, CI gate
           └─►  EVAL-C* scenario  ──►  scripts/burst.sh probe (black-box)  ← reviewer runs this
                                  └─►  evals/run.sh (spec → report)        ← full suite + artifacts
```

`InvariantsIT` proves it in-process; `burst.sh` proves it over HTTP against
*whatever URL you give it*; `evals/run.sh` runs every scenario and writes a
timestamped report under [`reports/`](reports/).

## Running

```bash
# fast gate — unit + Testcontainers integration
./mvnw verify

# black-box probes against a running instance
./scripts/burst.sh http://localhost:8080
./scripts/burst.sh https://<deployed-url>

# full scenario suite → evals/reports/<timestamp>.md
./evals/run.sh http://localhost:8080
```

Concurrency knobs: `N` (get-or-create fan-out), `K` (retry-storm fan-out),
`ROUNDS` (contention transfers), `MAX_INFLIGHT` (cap for the deployed free tier).

## Traceability

[`matrix.md`](matrix.md) maps every invariant → schema guard → unit test →
integration test → eval scenario → burst probe. No invariant without a row.

## Status

Implemented (TASK-12): `run.sh`/`lib.sh` runs the full scenario suite against any running
instance and writes a timestamped report to `evals/reports/`. See
[`evals/reports/20260911T033247Z.md`](reports/20260911T033247Z.md) for a real green run against
the local compose stack, and [`../docs/BUG-INJECTION-DEMO.md`](../docs/BUG-INJECTION-DEMO.md) for
proof the assertions catch a deliberately-broken invariant. CI runs it on every PR
(`.github/workflows/evals.yml`). EVAL-C6/O1/O2 are intentionally skipped by this runner — they're
asserted elsewhere (`EngineParityIT`, `bench/RESULTS.md`, `scripts/verify-container.sh` in CI's
container job) — see the report for details.
