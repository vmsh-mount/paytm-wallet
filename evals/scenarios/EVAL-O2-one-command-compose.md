# EVAL-O2 — One-command compose

| | |
|---|---|
| **Family** | Operational |
| **Invariant** | — |
| **Owning task** | TASK-10 |
| **Runs against** | local / CI |
| **Status** | Draft |

## Goal

`docker compose up` — a single command — brings up app + Postgres, applies
migrations, and yields a working API from a clean checkout.

## Preconditions

- Fresh `git clone` (or `git clean -xdf`), Docker + compose v2 installed.
- No pre-existing volumes (`docker compose down -v` first).

## Procedure

1. `time docker compose up --build -d --wait` (single command; `--wait` blocks on
   healthchecks).
2. Record time to all-healthy.
3. Smoke:
   - `POST /wallets {"user_id":"evalo2"}` → capture id.
   - `GET /wallets/{id}` → `balance_paise == 0`.
   - `POST /wallets` same user → same id.
4. Check migrations ran: `GET /actuator/health` shows `db` UP; (optional) exec
   `psql -c '\dt'` shows `wallets`, `transfers`, `flyway_schema_history`.
5. `docker compose down -v` → no dangling volumes (`docker volume ls` clean).

## Pass criteria

- [ ] A **single** `docker compose up` command (plus flags) is all that's needed
      — no pre-step, no manual migration, no seed.
- [ ] All services reach `healthy` (compose `--wait` exits 0) within ~90s.
- [ ] Smoke steps succeed.
- [ ] `flyway_schema_history` has ≥ 1 applied migration.
- [ ] `down -v` removes the pg volume; a second `up` starts clean.

## Fail signatures

- Requires `docker compose run app flyway migrate` or similar first → not
  one-command.
- App container restarts / crash-loops waiting for DB → missing
  `depends_on: condition: service_healthy`.
- `--wait` times out → healthchecks misconfigured.

## Artifacts captured

- `docker compose up` output + timing, smoke transcript, `\dt` output,
  `docker volume ls` before/after.
