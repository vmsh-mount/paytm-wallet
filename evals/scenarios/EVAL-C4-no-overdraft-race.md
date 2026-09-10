# EVAL-C4 — No-overdraft race

| | |
|---|---|
| **Family** | Correctness |
| **Invariant** | #2 No overdraft |
| **Owning task** | TASK-04 |
| **Runs against** | local compose + deployed URL |
| **Status** | Ready — `InvariantsIT.no_overdraft_under_contention`; live probe = `burst.sh` probe 3 overdraw variant (TASK-12) |

## Goal

Many concurrent debits of one thinly-funded wallet: exactly the affordable number
succeed, the rest are cleanly declined, the balance never goes negative and never
partially applies.

## Preconditions

- Wallet `S` (source) funded to `S0 = 100_000` paise.
- Wallet `D` (sink), `D0` recorded.
- `P` parallel transfers `S → D`, each `amount_paise = 10_000` → at most
  `S0 / 10_000 = 10` can succeed.

## Parameters

| Name | Default (local) | Default (deployed) | Meaning |
|------|-----------------|--------------------|---------|
| `P` | 40 | 25 | parallel debit attempts (≫ 10 affordable) |
| `UNIT` | 10_000 | 10_000 | per-transfer amount |

## Procedure

1. Record `S0`, `D0`.
2. Fire `P` transfers `S→D` of `UNIT`, **each with a fresh `idempotency_key`**, in
   parallel; `wait`.
3. Classify responses by `status`.
4. `GET /wallets/{S,D}`.

## Pass criteria

- [ ] `count(status == COMPLETED) == floor(S0 / UNIT) == 10`.
- [ ] `count(status == DECLINED, reason == insufficient_funds) == P - 10`.
- [ ] `S == S0 - 10*UNIT == 0` and `S >= 0` throughout.
- [ ] `D == D0 + 10*UNIT`.
- [ ] `Σ` conserved: `(S + D) == (S0 + D0)`.
- [ ] No `5xx`; no declined transfer left a `transfers` COMPLETED row or moved
      any money.
- [ ] `wallet_transfers_declined_total{reason="insufficient_funds"}` +=
      `P - 10`.

## Fail signatures

- `S < 0` → overdraft; the `balance >= amount` predicate isn't atomic with the
  debit (or was evaluated in app code with a stale read).
- `COMPLETED > 10` → lost update: two debits both read `balance` before either
  wrote.
- `D` increased by more than `10*UNIT` → credit applied for a declined transfer.
- `S + D != S0 + D0` → partial apply (debited, not credited, or vice versa).

## Artifacts captured

- Status histogram of the `P` responses, `S`/`D` before/after, `/metrics` delta,
  count of `transfers` rows by status (local).
