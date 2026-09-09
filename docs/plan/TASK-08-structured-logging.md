# TASK-08 — Structured logging & correlation id

| | |
|---|---|
| **Status** | Not started |
| **Branch** | `task/08-structured-logging` |
| **Depends on** | TASK-06 |
| **Invariant(s)** | — |
| **Est. effort** | ~2h |

## Context

The brief wants structured JSON logs with a per-request correlation id, logging
the *meaningful domain events* — and they must be publicly viewable. `CorrelationIdFilter`
and the logback JSON encoder are scaffolded; this task nails the event taxonomy
and makes the stream public.

## Goal

Every request emits a correlated access log; every money-relevant state change
emits a typed JSON event; the log stream is reachable by a public link.

## Scope

### In scope
- Domain event taxonomy (single `DomainEvents` helper, `event` field is an enum):
  | event | fields |
  |-------|--------|
  | `wallet.created` | `wallet_id, user_id` |
  | `transfer.received` | `transfer_id?, from, to, amount_paise, idempotency_key_hash` |
  | `transfer.debited` | `transfer_id, from, amount_paise, from_balance_after` |
  | `transfer.credited` | `transfer_id, to, amount_paise, to_balance_after` |
  | `transfer.completed` | `transfer_id, amount_paise, latency_ms` |
  | `transfer.declined` | `transfer_id, from, amount_paise, reason` |
  | `transfer.idempotent_replay` | `transfer_id, idempotency_key_hash` |
  | `transfer.conflict` | `idempotency_key_hash` |
- Every line: `correlation_id`, `service`, `level`, `ts`, `logger`, `event?`
- Access log (one line/request): method, path, status, `duration_ms`, `user_id`
- **Never log:** bearer tokens, full idempotency keys (log a short hash), PII
- `X-Correlation-Id` honored inbound, generated if absent, echoed in response
- MDC propagated across the transfer's worker path (no async hop currently, but
  assert it)
- Public logs: Render's log stream is public per-service **only via dashboard**;
  add a **log drain to a free viewer** (e.g. Better Stack / Grafana Cloud Loki
  free tier) OR a `/debug/logs/tail` SSE endpoint gated to a read-only token
  published in the submission. Decide in this task; document in README.

### Out of scope
- Log-based alerting
- Trace spans / OpenTelemetry (mention as the upgrade path)

## Design notes / decisions

- **`event` as a closed enum**, not free text — makes logs queryable
  (`event="transfer.declined"`) and greppable in the burst demo.
- **Hash, don't log, the idempotency key** — it's client-controlled and may be
  reused across users; a short prefix of SHA-256 is enough to correlate a retry
  storm in the logs without leaking it.
- **Balance-after on debit/credit events** gives a reviewer a running audit trail
  they can eyeball for conservation during a burst.
- **Public log choice:** prefer a **log drain to a free hosted viewer** with a
  shareable dashboard link over a custom endpoint — less code, no auth surface,
  and it survives app restarts. Fallback: screen-recording the Render stream
  during a burst (the brief explicitly allows this).

## Deliverables

- `observability/DomainEvents`, `observability/DomainEvent` enum
- `observability/AccessLogFilter`
- `CorrelationIdFilter` finalized + tests
- `logback-spring.xml` field tuning
- README "Observability → Logs" with the public link + example queries
- `docs/WRITEUP.md` — no dedicated section, but the submission "public logs link"

## Acceptance criteria

- [ ] A single transfer produces, in order: `transfer.received` → `transfer.debited`
      → `transfer.credited` → `transfer.completed`, all sharing one `correlation_id`
- [ ] A declined transfer produces `transfer.received` → `transfer.declined`
- [ ] A retry storm shows one `transfer.completed` + N−1 `transfer.idempotent_replay`
- [ ] `grep -c '"level":"ERROR"'` on a clean burst == 0
- [ ] No token or full idempotency key appears anywhere in output
- [ ] Public link renders the live stream (or recording captured)

## Test plan

### Unit
| Test | Asserts |
|------|---------|
| `CorrelationIdFilterTest` | inbound id honored; generated when absent; response header set; MDC cleared |
| `DomainEventsTest` | JSON shape per event; key hashed; no null-field noise |
| `AccessLogFilterTest` | one line/request, has status + duration + user |

### Integration
| Test | Asserts | Engines |
|------|---------|---------|
| `LoggingIT` | capture appender; assert event sequence for completed/declined/replay | conditional |

### Eval scenarios
| Scenario | Runs against | Pass criteria |
|----------|--------------|---------------|
| `EVAL-O3` | local + deployed | run `burst.sh`; assert correlated event sequences present; 0 ERROR; no secrets |

## Definition of done

- [ ] Event taxonomy implemented + tested
- [ ] Public log link in README (or recording committed to `docs/media/`)
- [ ] `EVAL-O3` doc written
- [ ] Branch squash-merged, status Done

## AI: directed vs decided

- **Directed**: closed event enum, hash-the-key rule, balance-after on events,
  drain-over-endpoint for public logs.
- **Decided**: which free log viewer, exact JSON field names, access-log format.
