# TASK-04 — Transfer engine v1: conditional debit

| | |
|---|---|
| **Status** | Done |
| **Branch** | `task/04-transfer-engine-conditional` |
| **Depends on** | TASK-01, TASK-03 |
| **Invariant(s)** | **#1 Conservation**, **#2 No overdraft** |
| **Est. effort** | ~4h |

## Context

The heart of the exercise. Given a validated `TransferRequest`, move money
atomically so that total balance is unchanged and no wallet goes negative — even
when A→B and B→A run at the same instant, and when many transfers contend on a
small wallet set.

## Goal

`ConditionalUpdateEngine` implemented; conservation and no-overdraft hold under
concurrent contention in `InvariantsIT`; the default engine wired end to end
(idempotency comes in TASK-05).

## Scope

### In scope
- `ConditionalUpdateEngine.execute(request, correlationId)`:
  1. open one tx (`READ COMMITTED`)
  2. lock the two wallet rows **in ascending `id` order** (`SELECT id FROM
     wallets WHERE id IN (?,?) ORDER BY id FOR UPDATE`) — deterministic order ⇒
     no deadlock
  3. debit: `UPDATE wallets SET balance_paise = balance_paise - :amt WHERE id =
     :from AND balance_paise >= :amt` — `rowsAffected == 0` ⇒ DECLINED
     (`insufficient_funds`), roll back, return
  4. credit: `UPDATE wallets SET balance_paise = balance_paise + :amt WHERE id =
     :to`
  5. insert `transfers` row `status = COMPLETED`
  6. commit
- `TransferService.create` validation: positive amount, `from != to`, both
  wallets exist, caller owns `from` (ownership check may be stubbed until TASK-06
  but the hook exists)
- Domain events: `transfer.created`, `transfer.debited`, `transfer.credited`,
  `transfer.declined`
- Metrics: `wallet.transfers.created`, `wallet.transfers.declined{reason=insufficient_funds}`
- Money declines are **not exceptions** — `Transfer{status=DECLINED}` returned,
  HTTP `201`/`200` (decide in TASK-06)

### Out of scope
- Idempotency key handling (TASK-05) — for now assume keys are unique per call
- `select-for-update` / `serializable` engines (TASK-07)
- The HTTP status-code contract (TASK-06)

## Design notes / decisions

- **Why the `FOR UPDATE` pre-lock *and* the conditional `UPDATE`?**
  - The conditional `UPDATE` alone gives atomic check+debit and is enough for
    no-overdraft. But to also guarantee **deterministic lock order** for the
    pair (debit row + credit row) and avoid an ABBA deadlock, we take both row
    locks up front sorted by id. The credit `UPDATE` then can't deadlock against
    a reverse transfer.
  - *Alternative considered:* skip the pre-lock, just do `UPDATE from` then
    `UPDATE to`. Deadlock risk: T1 (A→B) holds A wants B; T2 (B→A) holds B wants
    A. Postgres would detect and abort one with `40P01`, forcing a retry loop.
    The sorted pre-lock removes the possibility instead of recovering from it.
- **`READ COMMITTED` is sufficient** given row locks + the conditional predicate.
  No phantom/skew concern because we operate on two known rows by primary key.
- **Conservation proof sketch:** debit and credit are `- :amt` / `+ :amt` of the
  same integer in one tx; no partial commit; `wallets.balance_paise` has no other
  writer. Σ is invariant. The `CHECK (balance_paise >= 0)` is defense-in-depth
  (should never fire given the predicate).
- Heavier alternatives (`SELECT FOR UPDATE` + app check, `SERIALIZABLE`) are
  *implemented* in TASK-07 purely to benchmark and to make the write-up's
  "rejected alternatives" concrete rather than hypothetical.

## Deliverables

- `service/transfer/ConditionalUpdateEngine` (impl)
- `service/TransferService.create` (impl minus idempotency)
- `observability` domain events + counters wired
- `evals/scenarios/EVAL-C3-conservation-under-contention.md`
- `evals/scenarios/EVAL-C4-no-overdraft-race.md`
- `InvariantsIT`: `conservation_holds_under_concurrent_transfers`,
  `no_overdraft_under_contention`
- `docs/WRITEUP.md` §"Simplest-correct mechanism" (primary path)

## Acceptance criteria

- [ ] A(100) → B(0), transfer 30 ⇒ A=70, B=30, `transfer.status=COMPLETED`
- [ ] A(20) → B, transfer 50 ⇒ A=20, B unchanged, `status=DECLINED`,
      `reason=insufficient_funds`, no `transfers` COMPLETED row
- [ ] **Conservation:** 3 wallets seeded to known Σ; 200 concurrent transfers
      (mixed directions incl. A↔B simultaneously, random small amounts); after
      quiesce, `SUM(balance_paise)` == initial Σ **exactly**
- [ ] **No overdraft:** same run, every `balance_paise >= 0` at all times
      (assert final + a sampling probe); some transfers DECLINED, none partial
- [ ] **No deadlock:** the 200-transfer run completes with zero `40P01` SQL
      errors and no test timeout
- [ ] Single-threaded 1000 transfers: Σ constant, latency recorded

## Test plan

### Unit
| Test | Asserts |
|------|---------|
| `TransferServiceTest` | validation: amount ≤ 0 rejected, `from==to` rejected, unknown wallet → NotFound |
| `ConditionalUpdateEngineTest` (mocked jdbc) | `rowsAffected==0` path returns DECLINED without calling credit |

### Integration (`InvariantsIT`, Testcontainers)
| Test | Asserts | Engines |
|------|---------|---------|
| `conservation_holds_under_concurrent_transfers` | Σ exact after 200 concurrent mixed transfers | conditional (all 3 after TASK-07) |
| `no_overdraft_under_contention` | all balances ≥ 0; declines counted; no partial apply | conditional (all 3) |
| `reverse_transfers_do_not_deadlock` | A→B and B→A ×100 interleaved, 0 deadlock errors | conditional (all 3) |
| `declined_transfer_is_atomic` | after a decline, both balances byte-identical to before | conditional |

### Eval scenarios
| Scenario | Runs against | Pass criteria |
|----------|--------------|---------------|
| `EVAL-C3` | local + deployed | burst mixed transfers; `Σ_after == Σ_before`; min balance ≥ 0 |
| `EVAL-C4` | local + deployed | overdraw race; declined count > 0; no negative balance; Σ intact |

## Definition of done

- [ ] All acceptance criteria met; `InvariantsIT` green for the conditional engine
- [ ] `EVAL-C3`, `EVAL-C4` docs written
- [ ] `WRITEUP.md` §mechanism (primary) complete, deadlock paragraph included
- [ ] Micrometer counters visible at `/actuator/prometheus`
- [ ] Branch squash-merged, status Done

## AI: directed vs decided

- **Directed**: sorted `FOR UPDATE` pre-lock + conditional `UPDATE`,
  `READ COMMITTED`, declines-as-values-not-exceptions.
- **Decided**: SQL string formatting, the contention-test parameters (200/3),
  event field names.
