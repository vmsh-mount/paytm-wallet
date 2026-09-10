# Wallet & P2P Transfer — Write-up (one page)

> Skeleton. Fill each section as implementation lands.

## Data model

- `wallets(id, user_id UNIQUE, balance_paise BIGINT CHECK >= 0, created_at)`
- `transfers(id, from_wallet_id FK, to_wallet_id FK, amount_paise CHECK > 0, idempotency_key UNIQUE, request_fingerprint, status, decline_reason, created_at)` + `CHECK (from_wallet_id <> to_wallet_id)` + `CHECK (status <> 'DECLINED' OR decline_reason IS NOT NULL)`
- Money = integer **paise** everywhere, stored as `bigint` (max ≈ 9.2×10¹⁸ paise — no realistic overflow). No floats, no `NUMERIC` rupees (invites float thinking, slower).
- The V1 migration is **frozen** (header comment); Flyway validates checksums on boot, so drift fails fast. Further changes go to `V2+`.
- Three of the four invariants are made *impossible to violate* by the schema alone: overdraft (`CHECK balance_paise >= 0`), duplicate wallet (`UNIQUE user_id`), duplicate idempotency key (`UNIQUE idempotency_key`). Conservation is the one that still needs the service layer (debit+credit in one tx); the FKs at least guarantee both wallets exist.
- `request_fingerprint` = lowercase hex SHA-256 of `from|to|amount_paise`, stored on the row so a same-key replay can be checked without trusting the client body (TASK-05's 409 path).

**Why a balance column, not a double-entry ledger (chosen for R2).** `wallets.balance_paise` is the single source of truth, updated transactionally. One row lock per wallet; conservation is trivial when debit and credit share a transaction; every balance read is a single-row lookup.

- _Rejected — double-entry ledger_ (`entries(transfer_id, wallet_id, delta)`, balance = `SUM(delta)`): auditable and the "real" bank design, but every balance read becomes an aggregate or needs a maintained snapshot, and no-overdraft becomes "sum-for-update" — more machinery than this exercise needs. **Scale-up path:** move to this when an audit trail or per-entry reconciliation is required; keep the balance column as a materialised snapshot.

## Simplest-correct mechanism (conservation + no-overdraft)

- **Chosen — `ConditionalUpdateEngine`.** One `READ COMMITTED` transaction:
  1. `SELECT id FROM wallets WHERE id IN (:from,:to) ORDER BY id FOR UPDATE` — take **both** row locks up front, sorted by id.
  2. `UPDATE wallets SET balance_paise = balance_paise - :amt WHERE id = :from AND balance_paise >= :amt` — the check and the debit are one atomic statement. `rowsAffected == 0` ⇒ `DECLINED(insufficient_funds)`, no partial apply.
  3. `UPDATE wallets SET balance_paise = balance_paise + :amt WHERE id = :to`.
  4. `INSERT` the `transfers` row (COMPLETED or DECLINED) — same transaction, so it commits iff the money moved.
- **Deadlock-freedom:** the pre-lock is sorted by wallet id, so `A→B` and `B→A` running together both try to lock `min(A,B)` first — one waits, no ABBA cycle. Without the sorted pre-lock, `UPDATE from` then `UPDATE to` could deadlock (`40P01`) and force a retry loop; sorting removes the possibility rather than recovering from it.
- **Why `READ COMMITTED` is enough:** we touch exactly two rows by primary key, both write-locked; there is no phantom or read-skew surface. The `CHECK (balance_paise >= 0)` is defence-in-depth and should never fire given the predicate.
- **Conservation proof sketch:** `-amt` and `+amt` of the same integer, committed together; `wallets.balance_paise` has no other writer; no partial commit. Σ is invariant.
All three are implemented behind `TransferEngine` (`wallet.transfer.engine`, default `conditional-update`) and **all three pass the same `InvariantsIT` / `EngineParityIT` matrix** — correctness is not the differentiator. Cost under contention is; from `bench/RESULTS.md` (16 threads, 8 wallets, 10 s, local Postgres — see the file for the exact commit and re-run instructions):

| engine | throughput/s | p50 | p99 | retries | 503s | Σ conserved |
|--------|-------------:|----:|----:|--------:|-----:|:-----------:|
| conditional-update | **~7,800** | 1.0 ms | ~14 ms | 0 | 0 | ✓ |
| select-for-update | ~7,300 | 1.0 ms | ~12 ms | 0 | 0 | ✓ |
| serializable | ~3,400 | 0.3 ms | tens of ms | thousands | ~10–15 | ✓ |

- **Rejected — `SELECT … FOR UPDATE` + app-side check:** essentially the same throughput as the chosen engine (it holds the same two row locks for the same window), but an extra round trip to read the balance and a wider check-then-act than one conditional `UPDATE`. No reason to prefer it.
- **Rejected — `SERIALIZABLE`:** ~2.3× slower here and the only engine that shed load — thousands of retries and a handful of `SerializationExhausted` → `503` under this contention (retry budget 20), plus a p99 tail blown out by backoff waits. It moves the reasoning surface to the whole transaction's read/write set and adds a backoff/retry policy to defend. Correct, but more machinery for less throughput.
- Conditional-update wins on the shortest critical section (one statement does check + debit) and zero retry machinery. Verified by `InvariantsIT`: 200 concurrent mixed transfers (incl. A↔B simultaneously) → `SUM` exactly unchanged, `MIN` ≥ 0, zero deadlocks; a 100-way race on a wallet funded for 5 → exactly 5 COMPLETED.

## Where idempotency lives

- **The transfer row *is* the idempotency record** — `transfers.idempotency_key` is `UNIQUE`, and that row (key + `request_fingerprint` + status) is `INSERT`ed **in the same transaction** as the debit and credit. Commit is atomic, so the key exists iff the money moved — a crash between "debited" and "key recorded" is impossible (verified by `InvariantsIT.crash_between_debit_and_key_persists_nothing`: a tx aborted after the balance updates leaves original balances and no `transfers` row).
- Engine flow: pre-`SELECT` by key (cheap, unlocked); on miss, take the wallet `FOR UPDATE` locks, then **re-`SELECT` by key under the lock** (serialises identical-key retries — the retry storm does exactly one debit, the other K−1 see the row and return it); on miss again, debit → credit → `INSERT`.
- Retry, same body ⇒ return the stored transfer verbatim; `wallet.transfers.idempotent_replay` increments (replay only).
- Same key, **different body** ⇒ `409` — the stored `request_fingerprint` (lowercase hex SHA-256 of `from|to|amount_paise`) doesn't match, so we never trust the new body. Original row untouched.
- **Declines are idempotent too** — a re-sent DECLINED transfer returns the same DECLINED, no debit attempt.
- **HTTP surface:** a fresh transfer (COMPLETED *or* DECLINED) is `201`; an idempotent replay is `200` — so a client can distinguish "applied now" from "already processed". A declined transfer is a *successful* API call (`2xx`) that reports a business outcome; returning `4xx` would tell the client to fix its request, which is wrong. `409` only for a genuine key/body mismatch. `TransferEngine` returns a `TransferOutcome{transfer, replayed}` so the controller can pick `200` vs `201` without re-querying.
- Concurrent first-timers that slip past the locked re-check (only possible if a non-locking writer existed): the `UNIQUE` index rejects the second `INSERT`, that tx rolls back (balance changes undone), and the loser re-`SELECT`s the winner's row and returns it (or 409). This is a belt-and-suspenders fallback — the locked re-check makes it near-unreachable.
- _Rejected — a separate `idempotency_keys(key, response_json, created_at)` table:_ the standard API-gateway pattern, right when the operation spans services or you want to cache arbitrary responses. Here it's a second write and a two-phase "reserve key → do work → store response" with its own crash windows. One table, one unique index, one atomic commit is strictly simpler for a single DB.

## Race-free get-or-create (#4)

- `POST /wallets` = `INSERT INTO wallets (user_id) VALUES (?) ON CONFLICT (user_id) DO NOTHING`, then an **unconditional** `SELECT ... WHERE user_id = ?`. The `UNIQUE(user_id)` index is the single arbiter — the DB never creates a second row; a concurrent loser gets 0 rows affected and falls through to the same `SELECT`, so all N callers return the same wallet id. `userId` *is* the idempotency key here; no client key needed.
- Always returns **`200`** with `{id, balance_paise}` — "get or create" is one logical operation and the caller can't distinguish (or care) which half ran. New balance is `0`.
- _Rejected — `SELECT` then `INSERT` in app code:_ classic TOCTOU; two callers both read "absent", both insert, one eats a unique violation.
- _Rejected — advisory lock / `SERIALIZABLE`:_ heavier, and the unique index already gives the guarantee for free.
- Verified by `InvariantsIT.concurrent_get_or_create_yields_one_wallet`: 50 threads released from a `CyclicBarrier`, asserts one distinct id across all 50 responses, `count(*) == 1`, no 5xx.

## Auth

- Auth sophistication is explicitly not graded, so it is deliberately minimal: `Authorization: Bearer <token>` → `userId` via a static `token:userId` map from `wallet.auth.tokens` (`AUTH_TOKENS`, a `sync:false` secret on Render). No DB table, no issuance / refresh / expiry / JWT.
- A ~30-line servlet `Filter`, **not Spring Security** — mapping one header to one string does not justify Security's autoconfig and filter-chain surface. Reconsider only if the reviewer wants method-level security (noted).
- Filter order: `CorrelationIdFilter` → `AuthFilter`. Unknown/missing/malformed token → `401` `{error,message,correlation_id}`; `/actuator/**` stays open. Token compared constant-time (`MessageDigest.isEqual`) against every entry; only the resolved `userId` is logged / put in MDC, never the token.
- The caller-owns-the-source-wallet check is **not** here — it needs the wallet row, so it lives in `TransferService` (TASK-06). The filter only authenticates.

## Consistency vs availability

- Single Postgres, synchronous commits. Chosen **CP**: a partitioned / unreachable DB returns `5xx` rather than serving a possibly-stale balance or accepting a write it cannot durably record.
- Given up: write availability during DB downtime; horizontal write scaling; multi-region latency.
- _Acceptable because:_ TODO.

## AI: directed vs decided

| Area | Directed (I chose, AI typed) | Decided (I accepted AI's design) |
|---|---|---|
| Stack (Java 21 / Spring Boot 3 / JDBC) | ✅ | |
| Three swappable transfer engines | ✅ | |
| Maven wrapper (pinned 3.9.9) + Testcontainers-in-CI over H2 | ✅ | |
| GitHub Actions YAML, `.editorconfig` contents | | ✅ |
| Servlet filter (not Spring Security), static token map, `/actuator` allowlist | ✅ | |
| `RequestContext` as ThreadLocal, 401 JSON shape | | ✅ |
| ... | | |

## Build & tooling

- `./mvnw -B verify` is the single source of truth — Maven wrapper pinned to 3.9.9 so
  CI and a fresh clone build identically, no global Maven assumed. (The Dockerfile build
  stage still uses its base image's Maven; unifying on the wrapper is a TASK-10 cleanup.)
- Integration tests run against a real Postgres via Testcontainers (not H2): the
  invariants depend on Postgres semantics (`ON CONFLICT`, `FOR UPDATE`, `SERIALIZABLE`)
  that an embedded DB would fake. Cost: CI needs a Docker daemon (GitHub-hosted runners have one).
- **No license headers** on source files — single-repo take-home, not distributed; a header
  policy would be noise. Noted here so the omission is a decision, not an oversight.

## Free-tier cost note

- Render free web service + Render free managed Postgres. No card. **₹0.**
- Trade-offs: free web service sleeps after inactivity (cold start ~30–50s); free Postgres expires after 30 days / capped connections. Noted for the reviewer.
