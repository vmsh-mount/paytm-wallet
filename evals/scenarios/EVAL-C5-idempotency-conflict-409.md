# EVAL-C5 — Idempotency conflict (409)

| | |
|---|---|
| **Family** | Correctness |
| **Invariant** | #3 Exactly-once transfer (conflict path) |
| **Owning task** | TASK-05 |
| **Runs against** | local compose + deployed URL |
| **Status** | Draft |

## Goal

Reusing an `idempotency_key` with a different request body is a `409` conflict,
not a second debit and not a silent success.

## Preconditions

- Wallets `A` (funded `A0 = 200_000`), `B`, `C` created.
- One key: `KEY = "evalc5-$(uuidgen)"`.

## Parameters

_(none — sequential scenario)_

## Procedure

1. `POST /transfers {from:A, to:B, amount_paise:30_000, idempotency_key:KEY}` →
   expect `201 COMPLETED`. Record `A,B` after.
2. Re-send **identical** body, same `KEY` → expect `200`, identical body (control
   for EVAL-C2).
3. `POST` same `KEY`, `amount_paise: 30_001` (rest same) → **expect `409`**.
4. `POST` same `KEY`, `to: C` instead of `B` (amount 30_000) → **expect `409`**.
5. `POST` same `KEY`, `from: B` instead of `A` → **expect `409`**.
6. `GET /wallets/{A,B,C}`.

## Pass criteria

- [ ] Steps 3–5 each return HTTP `409` with error body `{error, message,
      correlation_id}`.
- [ ] After steps 3–5: `A == A0 - 30_000`, `B == B0 + 30_000`, `C == C0` —
      no additional movement.
- [ ] (local) still exactly one `transfers` row for `KEY`, unchanged since step 1.
- [ ] `wallet_transfers_conflict_total` += 3.
- [ ] The original transfer is still retrievable and `COMPLETED` via
      `GET /transfers/{id}`.

## Fail signatures

- Step 3/4/5 returns `200`/`201` → fingerprint check missing; a mutated transfer
  may have executed → check balances for a second debit.
- `409` but balances moved → conflict detected *after* applying; the check must
  precede the effect.
- `500` → conflict surfaced as an unhandled unique violation instead of a mapped
  `409`.

## Artifacts captured

- All 5 responses, `A/B/C` before and after, `transfers` row for `KEY` (local),
  `/metrics` conflict counter delta.
