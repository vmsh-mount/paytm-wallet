# TASK-11 — Deploy to Render + managed Postgres

| | |
|---|---|
| **Status** | Done — deployed 2026-09-11 to <https://p2p-wallet.onrender.com>, verified live (`scripts/smoke.sh`, `scripts/burst.sh`, `evals/run.sh` all green against it) |
| **Branch** | `task/11-deploy-render` |
| **Depends on** | TASK-08, TASK-09, TASK-10 |
| **Invariant(s)** | — (invariants re-verified against the live URL in TASK-12) |
| **Est. effort** | ~2.5h |

## Context

A genuinely deployed, publicly reachable API backed by a free managed Postgres,
at ₹0, no card. Render is the primary target (`render.yaml` scaffolded); Fly.io
is the documented fallback.

## Goal

A stable public URL serving the API over HTTPS, backed by Render's free managed
Postgres, with health checks green and logs + metrics publicly linkable.

## Scope

### In scope
- `render.yaml` blueprint finalized: `type: web`, `runtime: docker`, `plan: free`,
  `healthCheckPath: /actuator/health/readiness`
- Free managed Postgres (`databases: - plan: free`) wired via `fromDatabase`
- **`DATABASE_URL` format bridge:** Render provides a `postgres://user:pass@host/db`
  URL; the app needs JDBC. Handle in a tiny startup translation
  (`SPRING_DATASOURCE_URL` derivation) or an entrypoint shell step. Decide +
  document.
- `AUTH_TOKENS` as a `sync:false` secret; generate strong tokens for the
  submission, publish only the read-only/demo ones needed for grading
- Cold-start note: free web service sleeps after ~15 min idle → first request
  ~30–50s. Document; add a `GET /healthz` the reviewer can warm.
- DB free-tier caveats: expires ~30 days, ~1 GB, capped connections → set Hikari
  `maximum-pool-size` low (e.g. 5); document the expiry date in README
- Smoke test post-deploy: `scripts/smoke.sh <url>` (get-or-create, transfer,
  replay, metrics, health)
- `README` "Deployment" with URL, dashboard link, logs link, teardown steps
- Fallback: `fly.toml` + notes for Fly.io + Fly Postgres

### Out of scope
- CD auto-deploy on merge (mention; keep manual deploy for control)
- Custom domain, TLS certs (Render provides `*.onrender.com` HTTPS)

## Design notes / decisions

- **Render over Railway/Fly for the primary:** blueprint-as-code (`render.yaml`
  in repo = reproducible), free managed PG in the same product, no card. Fly is
  the fallback because its free allowances shifted and PG is self-managed.
- **One image, many envs** — the exact image from TASK-10 runs locally (compose)
  and on Render; only env vars differ. No "works in prod only" drift.
- **Pool size 5 on free PG** — the managed instance caps connections low;
  oversizing the pool causes `FATAL: too many connections` under the burst.
  Tune to the tier, document the number.
- **Keep-warm:** a tiny external cron (GitHub Actions scheduled workflow hitting
  `/healthz` every 10 min) keeps cold starts out of the grading burst — optional,
  documented, ₹0.

## Deliverables

- Final `render.yaml`, `fly.toml` (fallback), entrypoint/URL-bridge code
- `scripts/smoke.sh`
- `.github/workflows/keepwarm.yml` (optional, documented)
- README "Deployment" section: URL, logs link, dashboard link, PG expiry date,
  teardown
- `docs/WRITEUP.md` §"Free-tier cost note" (₹0, with the caveats)
- `evals/scenarios/EVAL-O5-deployed-smoke.md`

## Acceptance criteria

- [ ] Public HTTPS URL responds `200` on `/actuator/health` and `/healthz`
- [ ] `scripts/smoke.sh <url>` passes: create wallet → fund → transfer → replay →
      `/metrics` → `/dashboard`
- [ ] `/metrics` and `/dashboard` and the logs link are all publicly reachable
- [ ] Deploy is reproducible from `render.yaml` (documented steps, no console-only
      config beyond secrets)
- [ ] Hikari pool sized to the free tier; a 50-connection burst does not exhaust
      DB connections
- [ ] README lists the DB free-tier expiry date

## Test plan

### Unit
| Test | Asserts |
|------|---------|
| `DatabaseUrlBridgeTest` | `postgres://u:p@h:5432/db` → correct JDBC URL + creds |

### Integration
| Test | Asserts | Engines |
|------|---------|---------|
| `scripts/smoke.sh` (post-deploy, in CI against the live URL, non-blocking) | end-to-end happy path on real infra | conditional |

### Eval scenarios
| Scenario | Runs against | Pass criteria |
|----------|--------------|---------------|
| `EVAL-O5` | deployed | smoke walk green; health/metrics/dashboard/logs all public |

## Definition of done

- [ ] Live URL captured in README + memory
- [ ] `EVAL-O5` passing
- [ ] `WRITEUP.md` §cost note complete
- [ ] Branch squash-merged, status Done

## AI: directed vs decided

- **Directed**: Render primary + Fly fallback, one-image-many-envs, pool-to-tier,
  URL-bridge approach.
- **Decided**: entrypoint script details, keep-warm workflow, smoke-script steps.
