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

Scaffold — scenario specs are being written per task (each task that touches an
invariant delivers its `EVAL-*.md`). The runner (`run.sh`, `lib.sh`) lands in
TASK-12.
