# TASK-09 — Metrics & dashboard

| | |
|---|---|
| **Status** | Done |
| **Branch** | `task/09-metrics-dashboard` |
| **Depends on** | TASK-06, TASK-08 |
| **Invariant(s)** | — |
| **Est. effort** | ~2.5h |

## Context

The brief wants request rate, latency p99, error rate **plus domain counters**
(transfers created / declined-insufficient-funds / idempotent-replays), exposed
at `/metrics` or a small dashboard.

## Goal

`/metrics` (Prometheus text) served publicly with RED metrics + domain counters,
and a one-page dashboard (static HTML reading `/metrics`, or a Grafana Cloud
free snapshot) linked from README.

## Scope

### In scope
- Confirm `http.server.requests` timer: histogram on, p50/p95/p99 published
- Alias `/actuator/prometheus` → also serve at `/metrics` (brief's wording)
- Domain meters (`WalletMetrics`, wired in TASK-04/05, verified here):
  - `wallet_transfers_completed_total`
  - `wallet_transfers_declined_total{reason="insufficient_funds"}`
  - `wallet_transfers_idempotent_replay_total`
  - `wallet_transfers_conflict_total`
  - `wallet_transfer_amount_paise` (summary — distribution of transfer sizes)
  - `wallet_transfer_duration_seconds{engine,outcome}` (timer)
  - `wallet_transfer_retries_total{engine="serializable"}`
- `error rate` = ratio recipe documented (`5xx / total` over `http.server.requests`)
- Dashboard: **static `dashboard.html`** served by the app at `/dashboard`,
  polling `/metrics`, rendering ~8 tiles (rate, p99, error %, 4 domain counters,
  amount histogram). No external JS CDN dependency at deploy risk — inline.
- `/metrics` unauthenticated (allowlisted in TASK-02)
- Grafana Cloud free option documented as alternative

### Out of scope
- Alerting rules, SLO burn dashboards
- Per-user metrics (cardinality)

## Design notes / decisions

- **Serve both `/metrics` and `/actuator/prometheus`** — brief says `/metrics`;
  keep actuator for tooling. One `@GetMapping("/metrics")` delegating to the
  registry scrape.
- **Static self-hosted dashboard over Grafana** as the primary — zero extra infra,
  survives on the free tier, one link. Grafana Cloud free tier documented for the
  reviewer who wants historical graphs.
- **Domain timer tagged `{engine,outcome}`** so the same dashboard doubles as the
  TASK-07 benchmark view when `TRANSFER_ENGINE` is flipped live.
- **Avoid high-cardinality tags** — no `user_id`, no `wallet_id`, no
  `idempotency_key` on meters.
- Keep the histogram buckets explicit for latency so p99 is meaningful on low
  traffic.

## Deliverables

- `api/MetricsController` (`/metrics`)
- `observability/WalletMetrics` verified + extended (amount summary, tagged timer)
- `src/main/resources/static/dashboard.html` + `api` route `/dashboard`
- README "Observability → Metrics & Dashboard" with links + PromQL snippets
- `evals/scenarios/EVAL-O4-metrics-domain-counters.md`

## Acceptance criteria

- [ ] `GET /metrics` returns Prometheus text, HTTP 200, unauthenticated
- [ ] After a burst: `wallet_transfers_completed_total` and
      `wallet_transfers_declined_total` and `wallet_transfers_idempotent_replay_total`
      all reflect the burst's actual counts (±0)
- [ ] `http_server_requests_seconds{quantile="0.99"}` present and non-zero
- [ ] `/dashboard` renders all tiles from live data with no external network calls
- [ ] Error-rate recipe documented and yields a sane number during a fault test

## Test plan

### Unit
| Test | Asserts |
|------|---------|
| `MetricsControllerTest` | `/metrics` 200, content-type, contains domain meter names |
| `WalletMetricsTest` | counters increment on the right events only |

### Integration
| Test | Asserts | Engines |
|------|---------|---------|
| `MetricsIT` | drive 10 created + 3 declined + 5 replays → scrape asserts exact totals | conditional |

### Eval scenarios
| Scenario | Runs against | Pass criteria |
|----------|--------------|---------------|
| `EVAL-O4` | local + deployed | `burst.sh` records pre/post `/metrics`; deltas match probe expectations |

## Definition of done

- [ ] `/metrics` + `/dashboard` live and public
- [ ] Domain counter deltas verified against a burst
- [ ] `EVAL-O4` doc written; README links added
- [ ] Branch squash-merged, status Done

## AI: directed vs decided

- **Directed**: self-hosted static dashboard as primary, `/metrics` alias,
  low-cardinality rule, tagged domain timer.
- **Decided**: dashboard HTML/JS, histogram bucket boundaries, PromQL examples.
