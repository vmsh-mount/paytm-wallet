# TASK-12 — Burst script & correctness eval harness

| | |
|---|---|
| **Status** | Done |
| **Branch** | `task/12-burst-eval-harness` |
| **Depends on** | TASK-06, TASK-07 |
| **Invariant(s)** | **#1 #2 #3 #4** (black-box, against any URL) |
| **Est. effort** | ~3.5h |

## Context

The reviewer runs probes against the deployed URL. Ship a **one-command** script
that reproduces each, asserts the invariant, and exits non-zero on violation —
plus a small eval harness that turns the `evals/scenarios/*.md` specs into a
runnable, reportable suite.

## Goal

`./scripts/burst.sh <url>` runs all correctness probes and prints a pass/fail
table; `./evals/run.sh <url>` runs the full scenario suite (correctness +
operational) and writes `evals/reports/<timestamp>.md`.

## Scope

### In scope
- `scripts/burst.sh` — pure bash + curl + jq, background jobs + `wait` for
  concurrency, configurable `BASE_URL N K ROUNDS`, per-probe `PASS/FAIL` + exit
  code:
  - **Probe 1 — concurrent get-or-create:** N parallel `POST /wallets` new user →
    assert 1 unique id
  - **Probe 2 — idempotent retry storm:** fund A; one key; K parallel identical
    `POST /transfers` → assert dest credited once, all responses identical, then
    replay w/ different amount → assert `409`
  - **Probe 3 — conservation under contention:** seed A,B,C to known Σ; `ROUNDS`
    parallel mixed transfers (A→B, B→A, B→C, C→A, random amounts, some
    over-balance) → assert `Σ_after == Σ_before` and `min(balance) >= 0`; print
    declined count
  - **Probe 4 — no-overdraft race:** many parallel debits of one thinly-funded
    wallet → assert exactly `floor(balance/amount)` succeed, rest declined,
    balance ≥ 0
- Optional Go single-file variant (`scripts/burst.go`) for higher true
  concurrency (documented; bash is the canonical one)
- `evals/run.sh` — iterates scenarios, runs each probe/assertion, captures
  request/response artifacts + `/metrics` deltas + a log excerpt, emits a
  markdown report with a results matrix
- `evals/lib.sh` — shared assertions (`assert_eq`, `assert_http`, `sum_balances`,
  `parallel_curl`)
- Make `burst.sh` safe to run against the deployed free instance (bounded
  concurrency so we don't trip connection caps; warm-up call first)
- CI job: run `evals/run.sh` against the compose stack on every PR

### Out of scope
- Distributed load generation (k6/Gatling) — note as scale-up
- Chaos (killing the DB mid-burst) beyond the readiness check in TASK-10

## Design notes / decisions

- **Bash+curl canonical** — the brief lists it first and it has zero toolchain
  needs for the reviewer. Go variant only for when bash's fork-per-request
  concurrency is too weak to actually contend.
- **Assertions live in the script, not the reviewer's head** — each probe prints
  the computed vs expected and a verdict; exit code is the sum of failures.
- **`Σ` computed by summing `GET /wallets/{id}`** across the known set — black
  box, no DB access, same thing the reviewer would check.
- **Warm-up + bounded concurrency for the deployed target** — a free instance
  with pool=5 will 503 under 200 raw parallel curls; the script paces to
  `MAX_INFLIGHT` and still demonstrates contention (the DB serializes anyway).
  Document that local (compose) can push harder.
- **Eval report is an artifact** — committed under `evals/reports/` for the
  submission so the reviewer sees a green run even before running it themselves.

## Deliverables

- `scripts/burst.sh` (impl), `scripts/burst.go` (optional)
- `evals/run.sh`, `evals/lib.sh`
- `evals/reports/EXAMPLE.md` (a real run against the deployed URL)
- `.github/workflows/evals.yml`
- README "Burst probes" + "Evals" sections
- `docs/WRITEUP.md` — link the eval report

## Acceptance criteria

- [ ] `./scripts/burst.sh http://localhost:8080` → all 4 probes `PASS`, exit 0
- [ ] Introduce a deliberate bug (skip the `balance >= amount` predicate) → Probe
      3/4 `FAIL`, exit ≠ 0 (proves the assertions bite)
- [ ] `./scripts/burst.sh <deployed-url>` → all `PASS` within connection limits
- [ ] `./evals/run.sh` writes a report with a full green matrix + metric deltas +
      log excerpt
- [ ] CI `evals.yml` green on the compose stack
- [ ] Script has no dependency beyond `bash`, `curl`, `jq` (checked at start)

## Test plan

### Unit
| Test | Asserts |
|------|---------|
| `evals/lib_test.sh` (bats or plain) | `assert_eq`, `sum_balances`, `parallel_curl` behave |

### Integration
| Test | Asserts | Engines |
|------|---------|---------|
| `evals.yml` CI job | full `evals/run.sh` against compose passes for `TRANSFER_ENGINE=conditional` (matrix: all 3) | all 3 |
| bug-injection job (manual/documented) | assertions fail as designed | conditional |

### Eval scenarios
| Scenario | Runs against | Pass criteria |
|----------|--------------|---------------|
| `EVAL-C1..C6` | local + deployed | all green in the generated report |
| `EVAL-O1..O5` | local + deployed | all green in the generated report |

## Definition of done

- [ ] `burst.sh` + `evals/run.sh` implemented, all probes pass local + deployed
- [ ] Bug-injection demo documented (shows assertions are real)
- [ ] Example eval report committed
- [ ] CI evals job green
- [ ] Branch squash-merged, status Done

## AI: directed vs decided

- **Directed**: bash-canonical, in-script assertions, black-box Σ, warm-up +
  bounded concurrency for deployed, committed example report.
- **Decided**: exact probe parameters, report markdown layout, `lib.sh` API.
