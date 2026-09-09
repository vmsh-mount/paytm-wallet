# Wallet & P2P Transfer — Write-up (one page)

> Skeleton. Fill each section as implementation lands.

## Data model

- `wallets(id, user_id UNIQUE, balance_paise BIGINT CHECK >= 0, created_at)`
- `transfers(id, from_wallet_id FK, to_wallet_id FK, amount_paise CHECK > 0, idempotency_key UNIQUE, request_fingerprint, status, decline_reason, created_at)` + `CHECK (from_wallet_id <> to_wallet_id)`
- Money = integer **paise** everywhere, stored as `bigint` (max ≈ 9.2×10¹⁸ paise — no realistic overflow). No floats, no `NUMERIC` rupees (invites float thinking, slower).
- The V1 migration is **frozen** (header comment); Flyway validates checksums on boot, so drift fails fast. Further changes go to `V2+`.
- Three of the four invariants are made *impossible to violate* by the schema alone: overdraft (`CHECK balance_paise >= 0`), duplicate wallet (`UNIQUE user_id`), duplicate idempotency key (`UNIQUE idempotency_key`). Conservation is the one that still needs the service layer (debit+credit in one tx); the FKs at least guarantee both wallets exist.
- `request_fingerprint` = lowercase hex SHA-256 of `from|to|amount_paise`, stored on the row so a same-key replay can be checked without trusting the client body (TASK-05's 409 path).

**Why a balance column, not a double-entry ledger (chosen for R2).** `wallets.balance_paise` is the single source of truth, updated transactionally. One row lock per wallet; conservation is trivial when debit and credit share a transaction; every balance read is a single-row lookup.

- _Rejected — double-entry ledger_ (`entries(transfer_id, wallet_id, delta)`, balance = `SUM(delta)`): auditable and the "real" bank design, but every balance read becomes an aggregate or needs a maintained snapshot, and no-overdraft becomes "sum-for-update" — more machinery than this exercise needs. **Scale-up path:** move to this when an audit trail or per-entry reconciliation is required; keep the balance column as a materialised snapshot.

## Simplest-correct mechanism (conservation + no-overdraft)

- **Chosen:** row-locked conditional debit — `UPDATE wallets SET balance_paise = balance_paise - :amt WHERE id = :from AND balance_paise >= :amt`. `rowsAffected == 0` ⇒ declined, no partial apply. Credit is a second `UPDATE`. Both wallet rows touched in **ascending id order** ⇒ no deadlock when A→B and B→A race.
- **Rejected — `SELECT … FOR UPDATE` + app check:** correct, but two round trips and a wider check-then-act window than the single conditional statement.
- **Rejected — `SERIALIZABLE`:** correct, but forces a `40001` retry loop and moves the reasoning surface to the whole transaction's read/write set. Heavier to defend.
- All three are implemented behind `TransferEngine` (`wallet.transfer.engine`) for benchmarking; default `conditional-update`.
- _Benchmark numbers:_ TODO.

## Where idempotency lives

- `transfers.idempotency_key` is `UNIQUE`. The transfer row (with key) is inserted in the **same transaction** as the debit + credit — commit is atomic, so a key exists iff the money moved.
- Retry, same body ⇒ return the stored transfer (`wallet.transfers.idempotent_replay` counter).
- Same key, different body ⇒ `409` via `request_fingerprint` mismatch (hash of from+to+amount).
- _Race between two first-time requests with the same key:_ TODO (unique-violation ⇒ loser re-reads and returns winner's result).

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
