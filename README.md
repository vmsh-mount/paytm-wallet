# paytm-wallet

Wallet service with peer-to-peer transfers. Round-2 exercise — see [docs/PROBLEM_STATEMENT.md](docs/PROBLEM_STATEMENT.md).

**Status: scaffold.** Structure and infra are in place; business logic methods throw `UnsupportedOperationException` with `TODO(scaffold)` markers.

**Delivery plan:** [`docs/plan/`](docs/plan/README.md) — 14 tasks, each with scope, design decisions, acceptance criteria and a test plan. Worked one at a time.
**Evals:** [`evals/`](evals/README.md) — black-box invariant + operational scenario specs; traceability in [`evals/matrix.md`](evals/matrix.md).

## Stack

| Concern | Choice |
|---|---|
| Language / framework | Java 21, Spring Boot 3, `spring-boot-starter-jdbc` (raw SQL, no JPA) |
| DB | PostgreSQL 16, Flyway migrations |
| Concurrency mechanism | 3 swappable `TransferEngine`s — decide in code review (see [docs/WRITEUP.md](docs/WRITEUP.md)) |
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

Auth: `Authorization: Bearer <token>` → user id, from `AUTH_TOKENS` config.

## Run locally

```bash
docker compose up --build      # app + Postgres, one command, http://localhost:8080
```

Without Docker: `mvn spring-boot:run` (needs a local Postgres matching `application.yml` defaults).

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
  observability/  correlation-id filter, domain metrics
  config/         auth filter, engine selection
src/main/resources/
  db/migration/   Flyway SQL
  application.yml, logback-spring.xml
```

## Roadmap

Execution is tracked in [`docs/plan/README.md`](docs/plan/README.md) — 14 tasks,
5 milestones (M1 correct-core → M5 submitted). Start at
[TASK-00](docs/plan/TASK-00-build-tooling-ci.md) and work down; each task's
Definition of Done gates the next.
