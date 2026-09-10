# TASK-05 — Idempotency layer

| | |
|---|---|
| **Status** | Done |
| **Branch** | `task/05-idempotency` |
| **Depends on** | TASK-04 |
| **Invariant(s)** | **#3 Exactly-once transfer** |
| **Est. effort** | ~3h |

## Context

A retry storm of K identical `POST /transfers` (same `idempotency_key`) must
apply the transfer once and return identical responses. A reused key with a
*different* body is a `409`. The uniqueness must commit **in the same transaction**
as the debit/credit, or a crash between "money moved" and "key recorded" breaks
exactly-once.

## Goal

`ConditionalUpdateEngine` (and the interface contract for all engines) enforces
exactly-once via `UNIQUE(idempotency_key)` inserted in the money-movement tx;
replay returns the stored result; different-body replay → `409`.

## Scope

### In scope
- `RequestFingerprint.of(from, to, amount)` — SHA-256 hex
- Engine flow becomes:
  1. `SELECT * FROM transfers WHERE idempotency_key = ?`
     - hit + fingerprint match ⇒ return stored transfer, emit
       `transfer.idempotent_replay`, bump `wallet.transfers.idempotent_replay`
     - hit + fingerprint mismatch ⇒ throw `IdempotencyConflict` → `409`
  2. miss ⇒ run the transfer tx; the `INSERT INTO transfers(... idempotency_key,
     request_fingerprint ...)` is **inside the same tx** as the balance updates
  3. if that insert raises `unique_violation` (concurrent first-timers, same
     key) ⇒ roll back the balance changes, re-`SELECT` the winner's row, return
     it (or `409` if fingerprints differ)
- Idempotency also applied to `DECLINED` outcomes — a re-sent declined transfer
  returns the same DECLINED, does not retry the debit
- `TransferService` maps `IdempotencyConflict` → `409`

### Out of scope
- Idempotency for `POST /wallets` (not needed — `userId` is the key, TASK-03)
- A separate `idempotency_keys` table with TTL/response cache (documented as the
  general-purpose alternative)

## Design notes / decisions

- **Uniqueness on `transfers.idempotency_key`, not a side table.** The transfer
  row *is* the idempotency record. One insert, one unique index, committed atomically
  with the effect. A crash leaves either (no money moved, no key) or (money moved,
  key present) — never a gap.
  - *Rejected: separate `idempotency_keys(key, response_json, created_at)` table*
    — the standard API-gateway pattern, good when the operation spans services or
    you want to cache arbitrary responses. Here it's a second write and a
    two-phase "reserve key → do work → store response" with its own crash
    windows. The single-table approach is strictly simpler for one DB.
  - *Rejected: `INSERT … ON CONFLICT DO NOTHING` on the transfer then "did I win?"*
    — works, but the losing path still needs the re-select + fingerprint compare,
    so it doesn't save the branch; explicit pre-`SELECT` reads clearer.
- **Same-tx commit is the whole point** — call it out explicitly in the write-up
  and test it with a fault injection (kill the tx between debit and key insert →
  nothing persisted).
- **Fingerprint over storing the raw body** — smaller, and we only need
  equality. Excludes `idempotency_key` itself and any correlation/id fields.
- Concurrent first-timers: rely on the unique index to serialize; exactly one
  `INSERT` wins, others catch `unique_violation` and converge on the winner.

## Deliverables

- `idempotency/RequestFingerprint` (impl)
- `service/transfer/ConditionalUpdateEngine` idempotency branch (impl)
- `service/TransferService` → `409` mapping; `api/ApiExceptionHandler` case
- `evals/scenarios/EVAL-C2-idempotent-retry-storm.md`
- `evals/scenarios/EVAL-C5-idempotency-conflict-409.md`
- `InvariantsIT`: `same_idempotency_key_applies_once`,
  `same_key_different_body_is_409`, `crash_between_debit_and_key_persists_nothing`
- `docs/WRITEUP.md` §"Where idempotency lives"

## Acceptance criteria

- [ ] Same key, same body, sent twice sequentially ⇒ one debit; 2nd response
      byte-identical to 1st; one `transfers` row
- [ ] **K = 30 concurrent** identical `POST /transfers` ⇒ exactly one COMPLETED
      transfer, destination credited exactly once, all 30 responses identical,
      `count(*) WHERE idempotency_key = ?` == 1
- [ ] Same key, `amount_paise` changed ⇒ `409`, no second debit, original row
      untouched
- [ ] Re-send of a DECLINED transfer (same key) ⇒ same DECLINED response, no
      debit attempt
- [ ] Fault injection: abort tx after debit, before key insert ⇒ DB shows
      original balances and no `transfers` row
- [ ] `wallet.transfers.idempotent_replay` counter increments on replay only

## Test plan

### Unit
| Test | Asserts |
|------|---------|
| `RequestFingerprintTest` | stable across field order in construction; differs when amount differs; hex length 64 |
| `TransferServiceTest` | `IdempotencyConflict` → 409 mapping |

### Integration (`InvariantsIT`)
| Test | Asserts | Engines |
|------|---------|---------|
| `same_idempotency_key_applies_once` | K=30 concurrent, one effect, identical responses | all 3 |
| `same_key_different_body_is_409` | mismatch → 409, no mutation | all 3 |
| `idempotent_replay_of_declined_is_stable` | declined replay = declined, no debit | all 3 |
| `crash_between_debit_and_key_persists_nothing` | tx abort → no partial state | conditional |

### Eval scenarios
| Scenario | Runs against | Pass criteria |
|----------|--------------|---------------|
| `EVAL-C2` | local + deployed | K identical transfers; dest balance delta == amount (once); `uniq` responses == 1 |
| `EVAL-C5` | local + deployed | replay w/ changed amount → HTTP 409; balances unchanged |

## Definition of done

- [ ] Acceptance criteria met; `InvariantsIT` green all engines
- [ ] `EVAL-C2`, `EVAL-C5` docs written
- [ ] `WRITEUP.md` §idempotency complete — same-tx commit + rejected side-table
- [ ] Branch squash-merged, status Done

## AI: directed vs decided

- **Directed**: single-table uniqueness, same-tx commit, fingerprint approach,
  idempotent declines.
- **Decided**: hash input serialization format, conflict detection ordering
  (pre-SELECT vs catch-violation).
