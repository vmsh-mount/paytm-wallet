# Traceability matrix

Every invariant is guarded at the schema, proven in-process, and verified black-box.

## Invariants → verification

| Invariant | Schema guard | Unit test | Integration test (`InvariantsIT`) | Eval scenario | Burst probe | Owning task |
|-----------|--------------|-----------|-----------------------------------|---------------|-------------|-------------|
| **#1 Conservation** | debit+credit one tx; single balance writer | `ConditionalUpdateEngineTest` | `conservation_holds_under_concurrent_transfers` | `EVAL-C3` | Probe 3 | TASK-04 |
| **#2 No overdraft** | `CHECK (balance_paise >= 0)` + conditional `UPDATE` | `ConditionalUpdateEngineTest.declined_path` | `no_overdraft_under_contention`, `declined_transfer_is_atomic` | `EVAL-C4` | Probe 4 | TASK-04 |
| **#3 Exactly-once** | `UNIQUE(idempotency_key)` committed w/ effect; `request_fingerprint` | `RequestFingerprintTest`, `TransferServiceTest.conflict` | `same_idempotency_key_applies_once`, `same_key_different_body_is_409`, `crash_between_debit_and_key_persists_nothing` | `EVAL-C2`, `EVAL-C5` | Probe 2 | TASK-05 |
| **#4 Race-free get-or-create** | `UNIQUE(user_id)` + `ON CONFLICT DO NOTHING` | `WalletServiceTest` | `concurrent_get_or_create_yields_one_wallet` | `EVAL-C1` | Probe 1 | TASK-03 |
| deadlock-freedom (supports #1/#2) | sorted `FOR UPDATE` by id | — | `reverse_transfers_do_not_deadlock` | `EVAL-C3` (0 × `40P01`) | Probe 3 | TASK-04 |
| engine parity (#1–#3 hold for all 3) | — | per-engine unit tests | full IT matrix `@EnumSource` | `EVAL-C6` | `burst.sh` × 3 engines | TASK-07 |

## Operational requirements → verification

| Requirement (brief) | Artifact | Eval scenario | Owning task |
|---------------------|----------|---------------|-------------|
| Multi-stage, non-root, HEALTHCHECK | `Dockerfile` | `EVAL-O1` | TASK-10 |
| One-command app + Postgres | `docker-compose.yml` | `EVAL-O2` | TASK-10 |
| Deployed, free host + free managed PG, public URL | `render.yaml` | `EVAL-O5` | TASK-11 |
| Structured JSON logs, correlation id, domain events, public | `logback-spring.xml`, `DomainEvents` | `EVAL-O3` | TASK-08 |
| Metrics: RED + domain counters, `/metrics` or dashboard | `MetricsController`, `dashboard.html` | `EVAL-O4` | TASK-09 |
| One-command burst script | `scripts/burst.sh` | `EVAL-C1..C4` | TASK-12 |
| One-page write-up | `docs/WRITEUP.md` | manual review checklist (TASK-13) | TASK-13 |

## Consistency/availability posture

| Choice | Where enforced | Given up | Documented in |
|--------|----------------|----------|---------------|
| **CP** — single Postgres, synchronous commit, no stale reads | DB topology + `READ COMMITTED`/`SERIALIZABLE` | write availability during DB downtime; horizontal write scale; multi-region latency | `WRITEUP.md` §4, TASK-11 notes |
