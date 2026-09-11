# p2p-wallet

[![ci](../../actions/workflows/ci.yml/badge.svg)](../../actions/workflows/ci.yml)

Wallet service with peer-to-peer transfers. Round-2 exercise — see [docs/PROBLEM_STATEMENT.md](docs/PROBLEM_STATEMENT.md).

Build: `./mvnw -B verify` (Maven wrapper pinned to 3.9.9; needs a JDK 21+ and a Docker daemon for the Testcontainers-backed tests).

**Status: scaffold.** Structure and infra are in place; business logic methods throw `UnsupportedOperationException` with `TODO(scaffold)` markers.

**Delivery plan:** [`docs/plan/`](docs/plan/README.md) — 14 tasks, each with scope, design decisions, acceptance criteria and a test plan. Worked one at a time.
**Evals:** [`evals/`](evals/README.md) — black-box invariant + operational scenario specs; traceability in [`evals/matrix.md`](evals/matrix.md).

## Stack

| Concern | Choice |
|---|---|
| Language / framework | Java 21, Spring Boot 3, `spring-boot-starter-jdbc` (raw SQL, no JPA) |
| DB | PostgreSQL 16, Flyway migrations |
| Concurrency mechanism | 3 swappable `TransferEngine`s (`TRANSFER_ENGINE=conditional-update` \| `select-for-update` \| `serializable`); all pass the same invariant suite — benchmark in [`bench/RESULTS.md`](bench/RESULTS.md), rationale in [docs/WRITEUP.md](docs/WRITEUP.md) |
| Logs | JSON to stdout (logstash-logback), `correlation_id` per request via MDC |
| Metrics | Micrometer → Prometheus at `/actuator/prometheus`; domain counters in `WalletMetrics` |
| Deploy | Render blueprint (`render.yaml`) — free web service + free managed Postgres, ₹0 |

## API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/wallets` | get-or-create wallet for a user (race-free) |
| `GET` | `/wallets/{id}` | current balance |
| `POST` | `/transfers` | move money; body `from,to,amount_paise,idempotency_key` |
| `GET` | `/transfers/{id}` | transfer status |

Auth: `Authorization: Bearer <token>` → user id, from `AUTH_TOKENS` config. `/actuator/**` is open.

Full contract: [`docs/openapi.yaml`](docs/openapi.yaml).

### `POST /transfers` status codes

| Case | Code | Body |
|------|------|------|
| completed (fresh) | `201` | `TransferResponse{status:COMPLETED}` |
| declined — insufficient funds (fresh) | `201` | `TransferResponse{status:DECLINED, decline_reason}` |
| idempotent replay (same key + body) | `200` | stored `TransferResponse` |
| same key, different body | `409` | `ErrorResponse` |
| validation failure | `400` | `ErrorResponse` |
| caller not owner of `from` | `403` | `ErrorResponse` |
| `from` / `to` wallet unknown | `404` | `ErrorResponse` |
| unexpected | `500` | `ErrorResponse` (correlation id only, no stack) |

A **declined** transfer is a *successful* call reporting a business outcome — not a `4xx`.
`201` (new) vs `200` (replay) lets a client tell "my write happened now" from "already processed".
Every error body is `{error, message, correlation_id}`; the id matches the `X-Correlation-Id` response header.

## Run with Docker

```bash
docker compose up --build      # app + Postgres, one command → http://localhost:8080
docker compose down -v         # stop and wipe the DB volume
```

`cp .env.example .env` first to override `TRANSFER_ENGINE` / `AUTH_TOKENS` / `DB_POOL_MAX`.
Compose waits for Postgres to be healthy, then starts the app; Flyway migrates on boot; the app's
own healthcheck gates `healthy`.

Without Docker: `./mvnw spring-boot:run` (needs a local Postgres matching `application.yml` defaults).

### Image & hardening

| | |
|---|---|
| Base | `eclipse-temurin:21-jre-alpine` (build on `…-jdk-alpine` + the Maven wrapper), both **digest-pinned** |
| Size | ~100 MB (multi-stage; Spring Boot **layered jar** — deps / loader / snapshot-deps / application as separate image layers for rebuild cache) |
| User | non-root `app` (brief requirement); compose adds `read_only` rootfs + `tmpfs:/tmp` + `no-new-privileges` |
| Health | `HEALTHCHECK` → `GET /actuator/health/readiness` (busybox `wget`, no `curl` installed). **readiness = process up AND DB reachable**; **liveness = process up** — so a DB blip degrades readiness (LB stops routing) without triggering a restart loop |
| Migrations | in-app Flyway on startup — one moving part, same image on Render, no init container. Two replicas racing migrations is safe (Flyway takes a lock) |
| Scan | `trivy` (HIGH/CRITICAL, unfixed ignored) runs in CI, informational |

Harden-further path: distroless base (drops the shell — kept here for `HEALTHCHECK` + debugging on a free host). Multi-arch: `docker buildx build --platform linux/amd64,linux/arm64`.

`scripts/verify-container.sh` (run in CI) asserts all of the above end to end.

## Deployment

**Live URL:** _not yet deployed — see below._

One image (TASK-10's), many envs: the exact same Docker image runs locally via `docker compose`
and on the host below; only environment variables differ.

- **Primary: Render**, via the committed [`render.yaml`](render.yaml) blueprint — free web
  service (`runtime: docker`) + free managed Postgres, ₹0, no card.
  1. Push this repo to GitHub.
  2. On [render.com](https://render.com): **New → Blueprint**, point at the repo/branch.
     Render builds the `Dockerfile` and provisions the DB from `render.yaml` — no other
     console-only config.
  3. Set the `AUTH_TOKENS` secret in the dashboard (`sync: false` — never committed).
     Generate real tokens for the submission; only hand out the ones a reviewer needs.
  4. `healthCheckPath: /actuator/health/readiness` gates Render's own rollout health.
- **`DATABASE_URL` bridge:** Render hands the app `postgres://user:pass@host/db`;
  [`RenderDatabaseUrlEnvironmentPostProcessor`](src/main/java/com/paytm/wallet/config/RenderDatabaseUrlEnvironmentPostProcessor.java)
  splits it into `spring.datasource.{url,username,password}` at boot — no shell/entrypoint hack.
  Verified locally: booting the jar with `DATABASE_URL=postgres://wallet:wallet@localhost:5432/wallet`
  migrates, passes readiness, and serves `POST /wallets` exactly like the local `jdbc:` form.
- **Fallback: Fly.io** — [`fly.toml`](fly.toml) + setup notes in its header comment (Postgres is
  self-managed there, which is why Render is primary).
- **Free-tier caveats** (see `docs/WRITEUP.md` cost note): the web service sleeps after ~15 min
  idle (cold start ~30–50s) — `GET /healthz` is dependency-free for warming;
  [`.github/workflows/keepwarm.yml`](.github/workflows/keepwarm.yml) can ping it every 10 min
  (disabled until a `DEPLOYED_URL` repo variable is set). The managed Postgres expires ~30 days
  after creation and caps connections low — `DB_POOL_MAX=5` in `render.yaml` (default `10` locally).
- **Post-deploy smoke:** `./scripts/smoke.sh <url>` — get-or-create → transfer → idempotent
  replay → `GET /transfers/{id}` → `/metrics` → `/dashboard`. Set `SMOKE_FUND_SQL_URL` to a
  psql-reachable connection string to exercise a COMPLETED transfer (there is no deposit API by
  design — money only enters via a transfer from an already-funded wallet); without it, the script
  still runs end to end against the DECLINED path.
- **Public observability:** once deployed, this section gets the live URL, the Render log-stream
  link (or a `docs/media/` burst recording — TASK-08's fallback), and the dashboard/metrics links.
- **Teardown:** delete the Blueprint from the Render dashboard (removes the web service and the
  database together) — no other cleanup.

## Burst probes

```bash
./scripts/burst.sh https://your-deployed-url
```

## Layout

```
src/main/java/com/paytm/wallet/
  api/            controllers + DTOs + HTTP error mapping
  service/        WalletService, TransferService, domain exceptions
  service/transfer/  TransferEngine + 3 implementations
  repo/           JDBC repositories
  observability/  correlation-id + access-log filters, DomainEvents, WalletMetrics
  config/         auth filter, engine selection
src/main/resources/
  db/migration/   Flyway SQL
  application.yml, logback-spring.xml
```

## Observability → Logs

Structured JSON to stdout (one object per line). Every line carries `ts`, `level`, `logger`,
`message`, `service`, and — when the request set them — `correlation_id` and `user_id`.

- **Correlation id:** `X-Correlation-Id` is honoured inbound, generated if absent, echoed on the response, and threaded through every log line for that request.
- **Access log:** one `event=http.access` line per request — `method`, `path`, `status`, `duration_ms`, `user_id`.
- **Domain events** (`event=…`, closed set — see `observability/DomainEvent`):
  `wallet.created`, `transfer.received`, `transfer.debited` (+`from_balance_after`), `transfer.credited` (+`to_balance_after`), `transfer.completed` (+`latency_ms`), `transfer.declined` (+`reason`), `transfer.idempotent_replay`, `transfer.conflict`, `transfer.serialization_retry` / `…exhausted`.
- **Never logged:** bearer tokens; full `idempotency_key` (only `idempotency_key_hash`, a 12-hex SHA-256 prefix); PII.

Example queries (jq over the stream, or Loki/LogQL once drained):

```bash
# every event for one request
jq -c 'select(.correlation_id=="<id>")'
# running balance audit during a burst
jq -c 'select(.event=="transfer.debited" or .event=="transfer.credited")
       | {ts, event, transfer_id, from_balance_after, to_balance_after}'
# were there any errors?
jq -c 'select(.level=="ERROR")'
```

**Public link:** Render exposes a per-service log stream at the dashboard URL — added here once
deployed (TASK-11), alongside a screen recording of the stream during `./scripts/burst.sh` in
[`docs/media/`](docs/media/). Upgrade path: a log drain to Grafana Cloud Loki (free tier) with a
shareable dashboard — noted, not built (no code, survives restarts).

## Observability → Metrics & Dashboard

- **`GET /metrics`** — Prometheus text (0.0.4), unauthenticated. Same content as
  `GET /actuator/prometheus` (kept for tooling); `/metrics` is the brief's wording.
- **`GET /dashboard`** — a static one-pager (no external requests) polling `/metrics` every 3 s:
  request rate, http p99, error %, and the domain counters + transfer p99 + serializable retries.
- **RED** comes from `http_server_requests_seconds` (histogram + p50/p95/p99, explicit SLO buckets):
  - rate: `rate(http_server_requests_seconds_count[1m])`
  - p99: `http_server_requests_seconds{quantile="0.99"}`
  - error rate: `sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count[5m]))`
- **Domain meters** (low-cardinality — no user/wallet/key tags):

  | meter | meaning |
  |---|---|
  | `wallet_transfers_completed_total` | transfers that moved money |
  | `wallet_transfers_declined_total{reason="insufficient_funds"}` | clean declines |
  | `wallet_transfers_idempotent_replay_total` | repeat requests served from the stored row |
  | `wallet_transfers_conflict_total` | same key, different body (409) |
  | `wallet_transfer_amount_paise` | summary — distribution of completed transfer sizes |
  | `wallet_transfer_duration_seconds{engine,outcome}` | end-to-end timer — doubles as the engine-comparison view when `TRANSFER_ENGINE` is flipped |
  | `wallet_transfer_retries_total{engine="serializable"}` | 40001 retries |

  (`_created` is a reserved suffix in the Prometheus Java client, so the completed-transfer counter
  is `…_completed_total`, not `…_created_total`.)

Grafana Cloud free tier is the documented alternative for historical graphs; the self-hosted
static dashboard is the primary — zero extra infra, one link, survives the free tier.

## Roadmap

Execution is tracked in [`docs/plan/README.md`](docs/plan/README.md) — 14 tasks,
5 milestones (M1 correct-core → M5 submitted). Start at
[TASK-00](docs/plan/TASK-00-build-tooling-ci.md) and work down; each task's
Definition of Done gates the next.
