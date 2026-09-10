# EVAL-C2 — Idempotent retry storm

| | |
|---|---|
| **Family** | Correctness |
| **Invariant** | #3 Exactly-once transfer |
| **Owning task** | TASK-05 |
| **Runs against** | local compose + deployed URL |
| **Status** | Ready — `InvariantsIT.same_idempotency_key_applies_once_under_a_retry_storm` (K=30), `same_key_same_body_applied_once_sequentially`, `idempotent_replay_of_declined_is_stable`, `crash_between_debit_and_key_persists_nothing`; live probe = `burst.sh` probe 2 (TASK-12) |

## Goal

K concurrent identical `POST /transfers` (same `idempotency_key`) apply the
transfer once and return identical responses.

## Preconditions

- Wallet `A` created and funded to `A0 = 1_000_000` paise (via a seed transfer
  from a treasury wallet, or a test-only funding endpoint / SQL in local).
- Wallet `B` created, `B0` recorded.
- One generated key: `KEY = "evalc2-$(uuidgen)"`.
- Transfer body: `{from:A, to:B, amount_paise: 25_000, idempotency_key: KEY}`.

## Parameters

| Name | Default (local) | Default (deployed) | Meaning |
|------|-----------------|--------------------|---------|
| `K` | 30 | 20 | parallel identical POSTs |

## Procedure

1. Record `A0`, `B0` via `GET /wallets/{id}`.
2. Fire `K` identical `POST /transfers` (same body, same key) in parallel; `wait`.
3. Capture all `K` responses (status + body).
4. `GET /wallets/A`, `GET /wallets/B`.
5. Replay once more with `amount_paise: 25_001` (same `KEY`) → capture status.
6. `GET /transfers/{id}` for the returned transfer id.

## Pass criteria

- [ ] Exactly one response is `201` (created); the rest are `200` (replay) — OR
      all `K` are `200/201` **with byte-identical bodies** (implementation may not
      distinguish the winner under full concurrency; identical bodies is the hard
      requirement).
- [ ] All `K` bodies have the same `transfer.id` and `status == COMPLETED`.
- [ ] `A == A0 - 25_000` and `B == B0 + 25_000` — debited/credited **once**.
- [ ] (local) `SELECT count(*) FROM transfers WHERE idempotency_key = KEY` → `1`.
- [ ] Step 5 (different amount, same key) → `409`; `A`, `B` unchanged from step 4.
- [ ] `wallet_transfers_idempotent_replay_total` increased by `K-1` (±0) over the
      scenario.

## Fail signatures

- `B == B0 + 25_000 * m` with `m > 1` → double credit; idempotency not enforced
  or not committed with the effect.
- Differing response bodies (different `created_at`, `id`, or `status`) → replay
  returns a fresh computation instead of the stored result.
- Step 5 returns `200/201` → same-key/different-body not detected (fingerprint
  missing).
- `count(*) > 1` for the key → `UNIQUE(idempotency_key)` missing or inserted in a
  separate transaction.

## Artifacts captured

- All `K` bodies (hashed for the identical-check), `A`/`B` before/after,
  `/metrics` delta, the `409` response, `transfers` row count (local).
