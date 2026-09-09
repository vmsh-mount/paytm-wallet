# EVAL-C1 — Concurrent get-or-create

| | |
|---|---|
| **Family** | Correctness |
| **Invariant** | #4 Race-free get-or-create |
| **Owning task** | TASK-03 |
| **Runs against** | local compose + deployed URL |
| **Status** | Draft |

## Goal

N simultaneous `POST /wallets` for a brand-new user produce exactly one wallet.

## Preconditions

- A user id never seen before: `user = "evalc1-$(uuidgen)"`.
- A valid bearer token (any).

## Parameters

| Name | Default (local) | Default (deployed) | Meaning |
|------|-----------------|--------------------|---------|
| `N` | 50 | 30 | parallel create requests |
| `MAX_INFLIGHT` | N | 20 | cap concurrent sockets (free tier) |

## Procedure

1. Warm up: one `GET /actuator/health`.
2. Fire `N` `POST /wallets {"user_id": "<user>"}` in parallel (background curls,
   then `wait`), capture every response body + status.
3. Collect the `id` field from all `N` responses.
4. `GET /wallets/{id}` once for the observed id.

## Pass criteria

- [ ] All `N` requests return `200` (no `5xx`).
- [ ] `jq -r .id` over all responses yields **exactly one distinct value**.
- [ ] That wallet's `balance_paise == 0`.
- [ ] (local, DB check) `SELECT count(*) FROM wallets WHERE user_id = '<user>'` → `1`.

## Fail signatures

- ≥ 2 distinct wallet ids in responses → the create raced; unique index or
  `ON CONFLICT` missing.
- `500` with `duplicate key value violates unique constraint` → losing path not
  handled (should fall through to `SELECT`, not surface the violation).
- One id but `count(*) == 2` locally → impossible unless a second code path
  inserts wallets.

## Artifacts captured

- All `N` response bodies, the distinct-id set, the final `GET /wallets/{id}`,
  and (local) the `wallets` row count for the user.
