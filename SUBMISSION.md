# Submission

| | |
|---|---|
| **Repo** | <https://github.com/vmsh-mount/p2p-wallet> |
| **Live URL** | <https://p2p-wallet.onrender.com> |
| **Dashboard** | <https://p2p-wallet.onrender.com/dashboard> |
| **Metrics** | <https://p2p-wallet.onrender.com/metrics> |
| **Write-up** | [`docs/WRITEUP.md`](docs/WRITEUP.md) (longer form: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)) |
| **Eval report (live)** | [`evals/reports/20260911T050309Z.md`](evals/reports/20260911T050309Z.md) — 7/7 scored scenarios, exit `0` |
| **Bug-injection proof** | [`docs/BUG-INJECTION-DEMO.md`](docs/BUG-INJECTION-DEMO.md) |
| **Verified** | 2026-09-11 |

## Run the burst probes yourself

```bash
./scripts/burst.sh https://p2p-wallet.onrender.com
```

No setup needed — it's black-box (bash + curl + jq). Uses the default dev bearer tokens; against
this deployment those are still live for grading. No deposit API exists by design, so without
direct DB access the probes exercise the `DECLINED` path (documented in the script's own output,
not a failure) — see the linked report for a run that also confirms the `COMPLETED`/idempotent-
replay counters move.

## Caveats (free tier)

- **Cold start:** the web service sleeps after ~15 min idle. The **first** request after a sleep
  takes ~30–50s (Render spinning the container back up); every request after that is normal.
  `GET /healthz` is a dependency-free target if you want to warm it before running anything else.
- **DB lifetime:** the managed Postgres free instance expires **~30 days after creation**
  (created 2026-09-11 → expiry ~2026-10-11). If the live URL stops responding after that date,
  it's the DB expiry, not a bug — `docker compose up --build` reproduces the identical stack
  locally (same image, same migrations).

## Fresh-clone check (no tribal knowledge)

```bash
git clone https://github.com/vmsh-mount/p2p-wallet.git && cd p2p-wallet
docker compose up --build      # one command: app + Postgres
./scripts/burst.sh localhost:8080
```

## Repo map

- [`README.md`](README.md) — stack, API, run/deploy instructions
- [`docs/plan/`](docs/plan/README.md) — the 14-task delivery plan, one branch/PR per task
- [`evals/`](evals/README.md) — scenario specs + traceability matrix (`evals/matrix.md`)
- [`bench/RESULTS.md`](bench/RESULTS.md) — the 3-engine throughput/latency comparison
