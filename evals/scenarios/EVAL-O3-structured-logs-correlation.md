# EVAL-O3 — Structured logs & correlation id

| | |
|---|---|
| **Family** | Operational |
| **Invariant** | — |
| **Owning task** | TASK-08 |
| **Runs against** | local compose + deployed URL |
| **Status** | Ready — `LoggingIT` (completed/declined/replay-storm event sequences, one correlation id, 0 ERROR, no raw key) + `DomainEventsTest` / `CorrelationIdFilterTest` / `AccessLogFilterTest`; live capture via `./scripts/burst.sh` + Render stream (TASK-11) |

## Goal

Logs are structured JSON, every line carries a correlation id, domain events are
emitted for the meaningful money transitions, and no secrets leak.

## Preconditions

- Ability to capture app stdout (`docker compose logs app`, or the public log
  drain / Render stream for deployed).

## Procedure

1. Start capturing logs.
2. Issue three requests, each with a distinct `X-Correlation-Id`:
   - `POST /wallets` (new user) → `CID-1`
   - a COMPLETED transfer → `CID-2`
   - a DECLINED transfer (insufficient funds) → `CID-3`
   - a retry storm of 5 identical transfers → `CID-4`
3. Collect all log lines produced during the window.

## Pass criteria

- [ ] Every line is valid JSON (`jq -e . ` on each) with fields
      `ts, level, logger, correlation_id, service`.
- [ ] Lines for request `CID-n` all carry `correlation_id == CID-n` (inbound
      header honored).
- [ ] `CID-1` window contains `event:"wallet.created"`.
- [ ] `CID-2` window contains, in order, `transfer.received`, `transfer.debited`,
      `transfer.credited`, `transfer.completed` — same `correlation_id`, with a
      `transfer_id` linking them.
- [ ] `CID-3` window contains `transfer.received` then `transfer.declined` with
      `reason:"insufficient_funds"`.
- [ ] `CID-4` window contains exactly one `transfer.completed` and four
      `transfer.idempotent_replay`.
- [ ] `grep -c '"level":"ERROR"'` over the whole window == 0.
- [ ] No bearer token anywhere; no full `idempotency_key` (only a
      `idempotency_key_hash` prefix).
- [ ] Response carries `X-Correlation-Id` echoing the request's.

## Fail signatures

- Plain-text / multi-line stack logs → not structured.
- Missing `correlation_id` on some lines → MDC not set early enough (filter order).
- Events missing or out of order → taxonomy not wired at the right call sites.
- Token or raw key present → logging hygiene failure (hard fail).

## Artifacts captured

- The captured JSON log window, filtered per correlation id, plus the
  secret-scan grep results and the public log link.
