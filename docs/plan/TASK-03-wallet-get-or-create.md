# TASK-03 — Wallet get-or-create + balance read

| | |
|---|---|
| **Status** | Done |
| **Branch** | `task/03-wallet-get-or-create` |
| **Depends on** | TASK-01, TASK-02 |
| **Invariant(s)** | **#4 Race-free get-or-create** |
| **Est. effort** | ~2h |

## Context

`POST /wallets` is get-or-create keyed by `userId`. Under a burst of N concurrent
first-time calls, exactly one wallet row must result and every response must show
the same wallet id.

## Goal

`POST /wallets` and `GET /wallets/{id}` implemented and proven race-free by a
concurrent integration test.

## Scope

### In scope
- `WalletService.getOrCreate(userId)`, `WalletService.get(walletId)`
- `WalletRepository.insertIfAbsent(userId)` → `INSERT INTO wallets(user_id)
  VALUES (?) ON CONFLICT (user_id) DO NOTHING`
- `getOrCreate` = `insertIfAbsent` then `findByUserId` (always re-read; never
  trust the insert's own return for the row)
- `WalletController` wiring, `201` on create / `200` on get-existing (or always
  `200` — decide & document), `GET` returns `404` for unknown id
- Domain log events: `wallet.created`, `wallet.fetched`
- New balance is `0`

### Out of scope
- Any mutation of `balance_paise` (TASK-04)
- Listing a user's wallets

## Design notes / decisions

- **`INSERT … ON CONFLICT DO NOTHING` + re-select** is the simplest race-free
  get-or-create: the `UNIQUE(user_id)` index is the single arbiter, the DB never
  creates two rows, and losers fall through to the same `SELECT`.
  - *Rejected: `SELECT` then `INSERT` in app code* — classic TOCTOU; two callers
    both see "absent" and both insert, one gets a unique violation to handle.
  - *Rejected: advisory locks / `SERIALIZABLE`* — heavier, and the unique index
    already gives the guarantee for free.
- **Always re-read after insert.** `ON CONFLICT DO NOTHING` returns 0 rows for the
  loser; `RETURNING` only helps the winner. A unconditional `findByUserId` is
  uniform and correct for both.
- **Idempotent by nature** — no idempotency key needed here; `userId` *is* the
  key.
- Response code: return **`200` always** with `{id, balance_paise}` — "get or
  create" is one logical operation; documented so the reviewer isn't surprised.

## Deliverables

- `service/WalletService` (impl)
- `repo/WalletRepository.insertIfAbsent`, `findByUserId`, `findById` (impl)
- `api/WalletController` (impl), error mapping for `404`
- `evals/scenarios/EVAL-C1-concurrent-get-or-create.md`
- `InvariantsIT.concurrent_get_or_create_yields_one_wallet`
- `docs/WRITEUP.md` §"Race-free get-or-create"

## Acceptance criteria

- [ ] First `POST /wallets {user_id:"x"}` → new wallet, balance 0
- [ ] Second `POST` same user → same id, no new row (`SELECT count(*)` = 1)
- [ ] **N = 50 concurrent** `POST` for a brand-new user → exactly one row, all 50
      responses carry the same id, no 5xx
- [ ] `GET /wallets/{unknown}` → 404 JSON error with correlation id
- [ ] `GET /wallets/{id}` → correct balance

## Test plan

### Unit
| Test | Asserts |
|------|---------|
| `WalletServiceTest` | `getOrCreate` calls insert-then-read; maps repo empty → (impossible) guarded |
| `WalletControllerTest` (MockMvc) | 200 shape, 404 path, validation on blank `user_id` |

### Integration (`InvariantsIT`)
| Test | Asserts | Engines |
|------|---------|---------|
| `concurrent_get_or_create_yields_one_wallet` | N=50 threads via `CyclicBarrier`; `count(*) == 1`; all ids equal | n/a |
| `get_or_create_is_idempotent` | 5 sequential calls, same id | n/a |

### Eval scenarios
| Scenario | Runs against | Pass criteria |
|----------|--------------|---------------|
| `EVAL-C1` | local + deployed | fire N `POST /wallets` new user; `jq` unique id count == 1 |

## Definition of done

- [ ] Acceptance criteria met, `InvariantsIT` cases green
- [ ] `EVAL-C1` doc written; `burst.sh` probe 1 stub references it (impl in TASK-12)
- [ ] `WRITEUP.md` §#4 complete with rejected alternatives
- [ ] Branch squash-merged, status Done

## AI: directed vs decided

- **Directed**: `ON CONFLICT DO NOTHING` + re-read, always-`200` semantics.
- **Decided**: test concurrency harness (`CyclicBarrier` vs `ExecutorService`).
