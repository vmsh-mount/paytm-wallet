# TASK-10 — Containerization & one-command compose

| | |
|---|---|
| **Status** | Done |
| **Branch** | `task/10-containerization` |
| **Depends on** | TASK-06 |
| **Invariant(s)** | — |
| **Est. effort** | ~2h |

## Context

The brief is explicit: multi-stage Dockerfile, **non-root** user, `HEALTHCHECK`;
docker-compose bringing up app + Postgres with **one command**. Scaffold exists;
this task hardens and verifies it.

## Goal

`docker compose up` → healthy app + Postgres, migrations applied, one command,
no manual steps; image is small, non-root, and reports health accurately.

## Scope

### In scope
- Dockerfile:
  - multi-stage (build on `maven:3.9-eclipse-temurin-21`, run on
    `eclipse-temurin:21-jre-alpine`)
  - **non-root** `app` user, `USER app`, ownership of `/app`
  - `HEALTHCHECK` hitting `/actuator/health/readiness` (readiness = DB reachable)
  - layered jar or `-Dspring-boot.repackage.layers` for cache efficiency
  - `JAVA_OPTS` with container-aware `MaxRAMPercentage`
  - drop to `curl`-less healthcheck if we can (wget on alpine) — minimize
    installed packages; document
  - pinned base image digests
- `docker-compose.yml`:
  - `app` + `db`, `depends_on: condition: service_healthy`
  - named volume for PG data, healthchecks on both
  - single `docker compose up --build` brings everything up; `down -v` cleans
  - `.env.example` for overridable knobs
- `.dockerignore` minimal build context
- Image scan (`docker scout` / `trivy`) in CI, informational
- Document image size + layer breakdown in README

### Out of scope
- Kubernetes manifests / Helm
- Multi-arch build (note buildx one-liner)

## Design notes / decisions

- **Readiness vs liveness split:** liveness = process up (used by
  orchestrator restart); readiness = DB reachable + migrations done (used by
  load balancer + compose gate). Wrong split = traffic to an app that can't
  serve, or restart loops on transient DB blips.
- **Alpine JRE** (~180 MB total) vs distroless: alpine keeps a shell for
  `HEALTHCHECK` and debugging on a free host with no exec console guarantees.
  Distroless noted as the harden-further step.
- **Migrations run in-app on startup (Flyway)**, not a separate compose job —
  one moving part, and Render runs the same image with no init container.
  Trade-off: two app replicas racing migrations → mitigated by Flyway's lock;
  documented.
- **Non-root is non-negotiable per brief** — also set `read_only` rootfs +
  `tmpfs` for `/tmp` in compose as defense-in-depth, note if it complicates
  anything.

## Deliverables

- Hardened `Dockerfile`
- Hardened `docker-compose.yml` + `.env.example`
- CI step: build + trivy scan (non-blocking)
- README "Run with Docker" + image-size / security notes
- `evals/scenarios/EVAL-O1-container-hardening.md`
- `evals/scenarios/EVAL-O2-one-command-compose.md`

## Acceptance criteria

- [ ] `docker compose up --build` from a clean checkout → `app` healthy within
      60s, no manual step, migrations applied
- [ ] `docker inspect` shows the process user is **not root** (`"User": "app"`)
- [ ] `docker inspect --format '{{.Config.Healthcheck}}'` is non-empty and the
      container reaches `healthy`
- [ ] Killing `db` flips app readiness to DOWN; restoring `db` recovers without
      app restart
- [ ] Final image ≤ ~250 MB; `trivy` shows no HIGH/CRITICAL in app layers (or
      documented exceptions)
- [ ] `docker compose down -v` leaves no dangling volume

## Test plan

### Unit
| Test | Asserts |
|------|---------|
| — | (shell/CI assertions, not JUnit) |

### Integration
| Test | Asserts | Engines |
|------|---------|---------|
| `scripts/verify-container.sh` (CI) | user≠root, healthcheck present, compose up→healthy, db-down→not-ready | n/a |

### Eval scenarios
| Scenario | Runs against | Pass criteria |
|----------|--------------|---------------|
| `EVAL-O1` | local/CI | non-root + HEALTHCHECK + multi-stage assertions pass |
| `EVAL-O2` | local/CI | single `docker compose up` yields a working API (smoke `POST /wallets`) |

## Definition of done

- [ ] Both eval scenarios pass in CI
- [ ] README Docker section with size/security breakdown
- [ ] Branch squash-merged, status Done

## AI: directed vs decided

- **Directed**: readiness/liveness split, in-app Flyway, non-root + read-only
  rootfs, alpine-over-distroless with rationale.
- **Decided**: exact healthcheck command, layer config, trivy wiring.
