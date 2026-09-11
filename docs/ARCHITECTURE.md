# Architecture (longer form)

`docs/WRITEUP.md` is the one-page version graded directly; this fills in detail it had to cut.

## Race-free get-or-create (#4)

`POST /wallets` = `INSERT INTO wallets (user_id) VALUES (?) ON CONFLICT (user_id) DO NOTHING`,
then an **unconditional** `SELECT … WHERE user_id = ?`. The `UNIQUE(user_id)` index is the single
arbiter — a concurrent loser gets 0 rows affected and falls through to the same `SELECT`, so all N
concurrent callers return the same wallet id. `userId` *is* the idempotency key here; no client
key needed. Always `200` — "get or create" is one logical operation and the caller can't (and
shouldn't need to) distinguish which half ran.

Rejected: `SELECT` then `INSERT` in app code (classic TOCTOU — two callers both read "absent"),
and an advisory lock / `SERIALIZABLE` (heavier; the unique index already gives the guarantee for
free). Verified by `InvariantsIT.concurrent_get_or_create_yields_one_wallet`: 50 threads released
from a `CyclicBarrier`, one distinct id across all 50 responses, `count(*) == 1`, no `5xx`.

## Auth

Deliberately minimal — auth sophistication isn't graded. `Authorization: Bearer <token>` →
`userId` via a static `token:userId` map (`AUTH_TOKENS`, a `sync:false` secret on Render). No DB
table, no issuance/refresh/expiry/JWT. A ~30-line servlet `Filter`, not Spring Security — mapping
one header to one string doesn't justify Security's autoconfig surface. Filter order:
`CorrelationIdFilter` → `AuthFilter`; unknown/missing/malformed token → `401`; `/actuator/**` stays
open; tokens compared constant-time (`MessageDigest.isEqual`), never logged. The
caller-owns-the-source-wallet check lives in `TransferService`, not the filter — it needs the
wallet row.

## Build & tooling

`./mvnw -B verify` is the single source of truth — Maven wrapper pinned to 3.9.9 so CI and a fresh
clone build identically. Integration tests run against a real Postgres via Testcontainers (not
H2): the invariants depend on Postgres semantics (`ON CONFLICT`, `FOR UPDATE`, `SERIALIZABLE`)
that an embedded DB would fake. No license headers on source files — single-repo take-home, not
distributed; noted so the omission is a decision, not an oversight.

## Observability

- **Logs:** structured JSON to stdout (logstash-logback), one object per line — `ts`, `level`,
  `logger`, `message`, `service`, and (when set) `correlation_id`/`user_id`. `X-Correlation-Id` is
  honoured inbound, generated if absent, echoed on the response, threaded through every log line
  for that request. A closed set of domain events (`wallet.created`, `transfer.received`,
  `transfer.debited` +`from_balance_after`, `transfer.credited` +`to_balance_after`,
  `transfer.completed` +`latency_ms`, `transfer.declined` +`reason`, `transfer.idempotent_replay`,
  `transfer.conflict`, `transfer.serialization_retry`/`…exhausted`). Never logged: bearer tokens,
  full `idempotency_key` (only a 12-hex SHA-256 prefix), PII.
- **Metrics:** Micrometer → Prometheus at `/metrics` (alias of `/actuator/prometheus`). RED from
  `http_server_requests_seconds`; domain counters (`wallet_transfers_completed_total`,
  `wallet_transfers_declined_total{reason}`, `wallet_transfers_idempotent_replay_total`,
  `wallet_transfers_conflict_total`) and a tagged `wallet_transfer_duration_seconds{engine,outcome}`
  timer, all low-cardinality (no user/wallet/key tags).
- **Dashboard:** `GET /dashboard` — a static one-pager (no external requests) polling `/metrics`
  every 3s. Chosen as the primary observability surface over a hosted Grafana instance: zero
  infra, survives restarts, no third-party signup for a grader to hit.

## Containerization

Multi-stage `Dockerfile`: `eclipse-temurin:21-jdk-alpine` (build, digest-pinned) →
`…-jre-alpine` (runtime, digest-pinned), Spring Boot **layered jar** so dependency layers cache
across rebuilds. Non-root `app` user; compose adds `read_only` rootfs + `tmpfs:/tmp` +
`no-new-privileges`. `HEALTHCHECK` hits `/actuator/health/readiness` — **readiness = process up AND
DB reachable**, **liveness = process up only**, so a DB blip degrades readiness (load balancer
stops routing) without triggering a restart loop. Flyway migrates in-app on startup — one moving
part, same image on Render; two replicas racing migrations is safe (Flyway takes a lock).
`trivy` (HIGH/CRITICAL, unfixed ignored) runs in CI, informational only.
`scripts/verify-container.sh` asserts all of the above end to end, including the readiness/db-down
recovery path.

## Deploy

Render blueprint (`render.yaml`): free web service (`runtime: docker`) + free managed Postgres.
Render hands the app `postgres://user:pass@host/db`;
`RenderDatabaseUrlEnvironmentPostProcessor` splits it into `spring.datasource.{url,username,password}`
at boot — no shell/entrypoint hack, one code path for local `jdbc:` URLs and Render's form.
`fly.toml` documents a Fly.io fallback path (not exercised — Fly Postgres is self-managed, which is
why Render is primary).

## Eval harness

`scripts/burst.sh` — one-command, black-box HTTP probes (4 correctness invariants) against any
running instance; `evals/run.sh` — the full scenario suite, writing a timestamped report to
`evals/reports/`. Both are bash-3.2-safe (macOS's default shell): no associative arrays, no
`wait -n`, bounded concurrency via batched `wait`. There is no deposit API by design — money only
enters via a transfer from an already-funded wallet — so an optional direct-`psql` funding hook
(`FUND_SQL_URL`) lets a local/compose run exercise the `COMPLETED` path; without DB access (any
real deployed URL) the probes still run meaningfully against the `DECLINED` path, which is
documented in the harness output, not treated as a failure. See
[`docs/BUG-INJECTION-DEMO.md`](BUG-INJECTION-DEMO.md) for a demonstration that the assertions
catch a deliberately broken invariant, not just confirm a healthy one.
