# Delivery plan

The exercise is decomposed into **14 tasks**. Each task is a self-contained unit
with a fixed structure (see [`TEMPLATE.md`](TEMPLATE.md)): context, goal, scope,
design decisions, deliverables, acceptance criteria, an explicit **test plan**
(unit / integration / eval), a definition of done, and an AI directed-vs-decided
note.

Work proceeds **one task at a time**, top to bottom. A task is not "done" until
its acceptance criteria and test plan are green and the change is committed.

## Task index

| # | Task | Invariant(s) | Depends on | Status |
|---|------|--------------|------------|--------|
| [00](TASK-00-build-tooling-ci.md) | Build, tooling & CI baseline | — | — | Done |
| [01](TASK-01-data-model-migrations.md) | Data model & migrations | #1 #2 #3 #4 | 00 | Done |
| [02](TASK-02-bearer-auth.md) | Bearer-token auth & request context | — | 00 | Done |
| [03](TASK-03-wallet-get-or-create.md) | Wallet get-or-create + balance read | #4 | 01 02 | Done |
| [04](TASK-04-transfer-engine-conditional.md) | Transfer engine v1 — conditional debit | #1 #2 | 01 03 | Not started |
| [05](TASK-05-idempotency.md) | Idempotency layer | #3 | 04 | Not started |
| [06](TASK-06-transfer-api.md) | Transfer API, status & error contract | #1 #2 #3 | 04 05 | Not started |
| [07](TASK-07-alternative-engines.md) | Alternative engines + benchmark | #1 #2 | 04 06 | Not started |
| [08](TASK-08-structured-logging.md) | Structured logging & correlation id | — | 06 | Not started |
| [09](TASK-09-metrics-dashboard.md) | Metrics & dashboard | — | 06 08 | Not started |
| [10](TASK-10-containerization.md) | Containerization & one-command compose | — | 06 | Not started |
| [11](TASK-11-deploy-render.md) | Deploy to Render + managed Postgres | — | 08 09 10 | Not started |
| [12](TASK-12-burst-eval-harness.md) | Burst script & correctness eval harness | #1 #2 #3 #4 | 06 07 | Not started |
| [13](TASK-13-writeup-submission.md) | Write-up & submission package | — | all | Not started |

## Milestones

| Milestone | Tasks | Outcome |
|-----------|-------|---------|
| **M1 — Correct core (local)** | 00–07 | All four invariants pass locally under `InvariantsIT`, all three engines |
| **M2 — Observable** | 08–09 | JSON logs with correlation id + domain events; `/metrics` with RED + domain counters |
| **M3 — Deployed** | 10–11 | Public URL, managed Postgres, ₹0, public logs |
| **M4 — Provable** | 12 | `./scripts/burst.sh <url>` reproduces every probe with pass/fail exit codes |
| **M5 — Submitted** | 13 | One-page write-up, submission checklist complete |

## The four invariants (graded)

| # | Name | Enforced by (planned) | Verified by |
|---|------|-----------------------|-------------|
| 1 | **Conservation** — Σ balances constant across a transfer | debit+credit in one tx; no other balance writers | `EVAL-C3`, `InvariantsIT.conservation_*` |
| 2 | **No overdraft** — balance never < 0 | conditional `UPDATE … WHERE balance >= amount` + CHECK constraint | `EVAL-C4`, `InvariantsIT.no_overdraft_*` |
| 3 | **Exactly-once transfer** — same key ⇒ applied once | `UNIQUE(idempotency_key)` committed with debit/credit; `request_fingerprint` for 409 | `EVAL-C2`, `EVAL-C5`, `InvariantsIT.same_key_*` |
| 4 | **Race-free get-or-create** — one user ⇒ one wallet | `UNIQUE(user_id)` + `INSERT … ON CONFLICT DO NOTHING` | `EVAL-C1`, `InvariantsIT.concurrent_get_or_create_*` |

Full traceability: [`../../evals/matrix.md`](../../evals/matrix.md).

## Conventions

- One branch per task: `task/NN-short-slug`. Squash-merge to `main`.
- Commit message references the task: `TASK-04: conditional-debit engine + conservation IT`.
- Every task that touches an invariant adds/*extends* `InvariantsIT` **and** the
  matching `evals/scenarios/EVAL-*.md`.
- `docs/WRITEUP.md` sections are filled incrementally by the task that produces
  the relevant decision — never left to the end.
