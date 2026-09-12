# Wallet & P2P Transfer — Write-up

## Data model

- **Schema:** `wallets(id, user_id UNIQUE, balance_paise BIGINT CHECK >= 0)`;
  `transfers(id, from_wallet_id FK, to_wallet_id FK, amount_paise CHECK > 0, idempotency_key UNIQUE, request_fingerprint, status, decline_reason)` + `CHECK (from_wallet_id <> to_wallet_id)`.
- **Money** is integer **paise** (`bigint`) everywhere — never `NUMERIC`/float.
- **The schema alone makes 3 of the 4 invariants impossible to violate:** `CHECK balance_paise >= 0`,
  `UNIQUE user_id`, `UNIQUE idempotency_key`. Conservation is the one thing the service layer still
  owns (debit+credit in one transaction).
- **`request_fingerprint`** (SHA-256 of `from|to|amount_paise`) lets a same-key replay be checked
  without trusting the client body.
- **Rejected — double-entry ledger** (`entries(transfer_id, wallet_id, delta)`, balance = `SUM(delta)`):
  the auditable "real bank" design, but every balance read becomes an aggregate and no-overdraft
  becomes "sum-for-update" — more machinery than this exercise needs. Scale-up path: adopt it when
  an audit trail is required, keep `balance_paise` as a materialised snapshot.

## Simplest-correct mechanism (conservation + no-overdraft)

- **Chosen — `ConditionalUpdateEngine`**, one `READ COMMITTED` transaction: sorted `FOR UPDATE`
  pre-lock on both wallets → one `UPDATE … WHERE id=:from AND balance_paise >= :amt` (check + debit
  atomically; 0 rows ⇒ `DECLINED`) → credit → insert the `transfers` row, same transaction.
- **Why it's the simplest correct thing:** the check and the debit are a single atomic statement —
  there's no separate lock-then-check step to reason about, and no retry machinery to defend.
- **Deadlock avoidance:** the pre-lock is sorted by wallet id, so `A→B` and `B→A` both try `min(id)`
  first — one waits, neither cycles.
- **Why `READ COMMITTED` is enough:** exactly two rows are touched, by primary key, both
  write-locked — no phantom/read-skew surface exists to exploit. The `CHECK` constraint is
  defence-in-depth, not the primary guard.
- **Benchmarked, not asserted** — all three engines pass the identical invariant test matrix; cost
  under contention is the actual differentiator (`bench/RESULTS.md`, 16 threads / 8 wallets / 10s,
  local Postgres):

  | engine | throughput/s | p99 | retries | 503s |
  |--------|------:|----:|--------:|-----:|
  | **conditional-update** | **~7,800** | ~14 ms | 0 | 0 |
  | select-for-update | ~7,300 | ~12 ms | 0 | 0 |
  | serializable | ~3,400 | tens of ms | thousands | ~10–15 |

- **Rejected — `SELECT … FOR UPDATE` + app-side check:** holds the same locks for the same window,
  plus an extra round trip — no reason to prefer it.
- **Rejected — `SERIALIZABLE`:** ~2.3× slower here, the only engine that shed load (thousands of
  retries, occasional `503`s under this contention). It moves the reasoning surface to the whole
  transaction's read/write set and needs a backoff policy to defend.

## Where idempotency lives

- **Enforced by:** `UNIQUE(idempotency_key)` on the `transfers` row itself — the row *is* the
  idempotency record, not a separate structure.
- **Same transaction as the debit/credit:** yes — the key is inserted alongside the balance
  changes, so it exists iff the money moved. No crash window between "debited" and "key recorded"
  (verified by `InvariantsIT.crash_between_debit_and_key_persists_nothing`).
- **Flow:** pre-`SELECT` by key (cheap, unlocked) → take the wallet locks → **re-`SELECT` by key
  under the lock** (this is what makes a K-way retry storm debit exactly once — the other K−1 see
  the row and return it) → debit → credit → insert.
- **Same key + same body** ⇒ return the stored transfer verbatim (`200`, replay).
- **Same key + different body** ⇒ `409` — the stored `request_fingerprint` doesn't match, so the
  new body is never trusted; the original row is untouched.
- **Declines replay idempotently too** — no debit attempt on a repeat.
- **Status-code contract:** a fresh transfer (`COMPLETED` or `DECLINED`) is `201`; a replay is
  `200` — the client can tell "applied now" from "already processed" without inspecting the body.
- **Rejected — a separate `idempotency_keys` table:** the API-gateway pattern for when the
  operation spans services; here it's a second write and a "reserve → do work → store" sequence
  with its own crash windows. One unique index, one atomic commit is strictly simpler for a single DB.

## Consistency vs availability

- **Chosen: CP.** Single Postgres, synchronous commits — a partitioned/unreachable DB returns
  `5xx` rather than serve a stale balance or accept a write it can't durably record.
- **Given up:** write availability during DB downtime, horizontal write scaling, multi-region latency.
- **Why that trade is right here:** a wallet ledger must never silently diverge from its true
  balance. An unavailable response is recoverable — retry. A wrong balance is not.

## AI: directed vs decided

| Area | Directed (I chose) | Decided (AI's call) |
|---|---|---|
| Balance column not ledger; paise as `bigint`; fingerprint definition | ✅ | |
| `ON CONFLICT` get-or-create; sorted `FOR UPDATE` + conditional `UPDATE` | ✅ | |
| Single-table idempotency, same-tx commit; declined-is-`201`/replay-is-`200` | ✅ | |
| Filter-not-Spring-Security auth; implement all 3 engines to compare | ✅ | |
| Closed log-event enum, hash-the-key rule; static dashboard as primary surface | ✅ | |
| Non-root/read-only rootfs, readiness≠liveness split, alpine over distroless | ✅ | |
| Render primary + Fly fallback; bash-canonical black-box harness | ✅ | |
| Exact GitHub Actions YAML / `.editorconfig`; `RequestContext` code style | | ✅ |
| Benchmark harness shape, workload-mix constants; dashboard HTML/JS | | ✅ |
| Healthcheck command, entrypoint details; report markdown layout, `lib.sh` API | | ✅ |

## Free-tier cost note

- **Render free web service + Render free managed Postgres — ₹0, no card.** One Docker image runs
  identically via `docker compose` locally and on Render; only env vars differ.
- **Cold start:** the free web service sleeps after ~15 min idle — first request after sleep is
  ~30–50s. `GET /healthz` is a dependency-free warm-up target; `.github/workflows/keepwarm.yml`
  can ping it every 10 min (disabled by default).
- **DB lifetime:** the free managed Postgres **expires ~30 days after creation** and caps
  connections low — `DB_POOL_MAX=5` on Render (vs `10` locally) keeps Hikari inside that cap under
  a burst.
- **Fallback:** `fly.toml` documents a Fly.io path (not exercised — Fly Postgres is self-managed,
  which is why Render is primary).

---

Longer form: [`docs/ARCHITECTURE.md`](ARCHITECTURE.md). Evals: [`evals/README.md`](../evals/README.md),
a real green run against the live deployment at
[`evals/reports/20260911T050309Z.md`](../evals/reports/20260911T050309Z.md), and proof the
assertions catch a broken invariant at [`docs/BUG-INJECTION-DEMO.md`](BUG-INJECTION-DEMO.md).
