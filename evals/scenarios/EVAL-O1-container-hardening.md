# EVAL-O1 — Container hardening

| | |
|---|---|
| **Family** | Operational |
| **Invariant** | — |
| **Owning task** | TASK-10 |
| **Runs against** | local / CI |
| **Status** | Draft |

## Goal

The image is multi-stage, runs as a non-root user, and has a working
`HEALTHCHECK` — the brief's three explicit container requirements.

## Preconditions

- `docker build -t paytm-wallet:eval .` succeeds.

## Procedure

1. `docker build` and record final image size + `docker history` layer count.
2. `docker inspect paytm-wallet:eval --format '{{.Config.User}}'`.
3. `docker inspect --format '{{json .Config.Healthcheck}}'`.
4. `docker run` with a reachable DB; poll `docker inspect --format
   '{{.State.Health.Status}}'` until `healthy` or 60s timeout.
5. Confirm build is multi-stage: `grep -c '^FROM ' Dockerfile` ≥ 2 and the
   runtime stage has no Maven/JDK-build tooling (`docker run ... which mvn` fails).
6. `docker run ... id` → uid ≠ 0.
7. (optional) `trivy image paytm-wallet:eval` → no HIGH/CRITICAL in app layers.

## Pass criteria

- [ ] `.Config.User` is `app` (or a numeric non-zero uid), **not** empty/`root`.
- [ ] `id -u` inside the container ≠ `0`.
- [ ] `.Config.Healthcheck` is non-empty (Test, Interval, Timeout, Retries set).
- [ ] Container reaches `healthy` within 60s with a DB present.
- [ ] `Dockerfile` has ≥ 2 `FROM` stages; runtime stage lacks build tools.
- [ ] Final image ≤ ~250 MB.
- [ ] (if run) trivy: no unaddressed HIGH/CRITICAL.

## Fail signatures

- `.Config.User` empty → runs as root (brief violation).
- `.Config.Healthcheck` null → no HEALTHCHECK.
- Never reaches `healthy` → healthcheck command wrong or readiness endpoint
  missing.
- Runtime image contains `mvn`/`javac` → not a real multi-stage split; bloated.

## Artifacts captured

- `docker inspect` excerpts (User, Healthcheck, Health.Status), image size,
  layer count, trivy summary.
