# EVAL-O4 — Metrics & domain counters

| | |
|---|---|
| **Family** | Operational |
| **Invariant** | — |
| **Owning task** | TASK-09 |
| **Runs against** | local compose + deployed URL |
| **Status** | Draft |

## Goal

`/metrics` exposes request rate, latency p99, error rate, **and** the domain
counters (transfers created / declined-insufficient-funds / idempotent-replays),
and the numbers move correctly in response to real traffic.

## Preconditions

- Wallets seeded so the scripted traffic below is deterministic:
  `A` funded enough for `C` completed transfers but not the `D` over-balance ones.

## Parameters

| Name | Default | Meaning |
|------|---------|---------|
| `C` | 10 | transfers that should COMPLETE |
| `D` | 4 | transfers that should be DECLINED (insufficient funds) |
| `R` | 6 | idempotent replays (1 real + 5 repeats of one key) |

## Procedure

1. `GET /metrics` → snapshot `M0` (parse the relevant series).
2. Run scripted traffic: `C` distinct completed transfers, `D` over-balance
   transfers, then `R` sends of a single new keyed transfer.
3. `GET /metrics` → snapshot `M1`.
4. Compute deltas.

## Pass criteria

- [ ] `GET /metrics` → `200`, Prometheus text format, **no auth required**.
- [ ] Series present: `http_server_requests_seconds{quantile="0.99"}`,
      `http_server_requests_seconds_count`,
      `wallet_transfers_created_total`,
      `wallet_transfers_declined_total{reason="insufficient_funds"}`,
      `wallet_transfers_idempotent_replay_total`.
- [ ] `Δ wallet_transfers_created_total == C + 1` (the `R` group's first send).
- [ ] `Δ wallet_transfers_declined_total{reason="insufficient_funds"} == D`.
- [ ] `Δ wallet_transfers_idempotent_replay_total == R - 1`.
- [ ] `Δ http_server_requests_seconds_count == C + D + R` (+ the 2 scrapes if the
      endpoint counts itself — document which).
- [ ] p99 latency series is present and > 0.
- [ ] `/dashboard` renders these values with no external network calls.

## Fail signatures

- `/metrics` needs a token → not publicly scrapeable (brief wants it exposed).
- Counter deltas ≠ expected → meters incremented at the wrong call site, or
  double-counted (e.g. on both service and controller).
- Domain counters absent → only RED metrics wired, brief's explicit ask missed.
- p99 missing → histogram/percentiles not enabled for `http.server.requests`.

## Artifacts captured

- `M0` and `M1` scrapes, computed deltas vs expected table, `/dashboard`
  screenshot.
