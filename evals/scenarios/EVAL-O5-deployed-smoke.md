# EVAL-O5 — Deployed smoke

| | |
|---|---|
| **Family** | Operational |
| **Invariant** | — (invariants covered by C1–C4 against the same URL) |
| **Owning task** | TASK-11 |
| **Runs against** | deployed URL |
| **Status** | Prepared, blocked on an actual deploy — `scripts/smoke.sh <url>` implements steps 1–10 (`SMOKE_FUND_SQL_URL` covers step 4's funding); the `DATABASE_URL` bridge it depends on is verified end-to-end locally (a `postgres://` URL against real Postgres → Flyway migrates, `/healthz`, readiness, `POST /wallets` all correct). Needs a live Render URL to actually run — TASK-11 can prepare everything except clicking "Deploy" on someone's Render account. |

## Goal

The deployed instance is genuinely up: HTTPS, health, auth, the full API happy
path, and public observability — end to end on real free-tier infra.

## Preconditions

- Public base URL (e.g. `https://p2p-wallet.onrender.com`).
- A demo bearer token published in `SUBMISSION.md`.

## Procedure

1. `GET /healthz` and `GET /actuator/health` (may take ~40s if the free instance
   is asleep — retry once after a warm-up).
2. Unauthenticated `POST /wallets` → expect `401`.
3. Authenticated: create `A`, create `B`.
4. Fund `A` (treasury transfer or documented seed path).
5. `POST /transfers A→B` with a fresh key → `201 COMPLETED`.
6. Replay same key → `200`, identical body.
7. Replay same key, different amount → `409`.
8. `GET /transfers/{id}` → `COMPLETED`.
9. `GET /metrics` → `200`, contains domain counters.
10. `GET /dashboard` → `200`, renders.
11. Open the public logs link → shows the lines from steps 3–8 with correlation
    ids.

## Pass criteria

- [ ] All endpoints reachable over **HTTPS**; TLS valid.
- [ ] Health endpoints `200` (after warm-up); readiness reflects DB.
- [ ] Steps 2, 5, 6, 7, 8 return the documented status codes.
- [ ] `A`/`B` balances consistent with a single `A→B` transfer.
- [ ] `/metrics`, `/dashboard`, logs link all load **without auth**.
- [ ] DB is the free managed Postgres (documented), Hikari pool sized to tier;
      the 50-request EVAL-C1/C3 burst against this URL does **not** exhaust DB
      connections.
- [ ] Total running cost = ₹0 (documented in `WRITEUP.md`).

## Fail signatures

- HTTP (not HTTPS) or cert error → deploy misconfigured.
- `503`/connection errors under the C-series burst → pool oversized for the free
  DB tier, or instance under-resourced.
- Logs/metrics links require login → not "publicly viewable".
- Health green while DB is down → readiness not wired to the datasource.

## Artifacts captured

- Full request/response transcript, the burst-against-prod result (links to
  C1/C3 reports run with the deployed URL), screenshots of dashboard + logs,
  the cost note.
