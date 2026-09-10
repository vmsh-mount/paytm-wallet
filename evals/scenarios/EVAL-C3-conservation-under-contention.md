# EVAL-C3 — Conservation under contention

| | |
|---|---|
| **Family** | Correctness |
| **Invariant** | #1 Conservation (+ deadlock-freedom) |
| **Owning task** | TASK-04 |
| **Runs against** | local compose + deployed URL |
| **Status** | Ready — `InvariantsIT.conservation_holds_under_concurrent_transfers` + `reverse_transfers_do_not_deadlock`; live probe = `burst.sh` probe 3 (TASK-12) |

## Goal

Under many concurrent transfers among a small wallet set — including A→B and B→A
at the same instant — total balance is unchanged and no balance goes negative.

## Preconditions

- Wallets `A`, `B`, `C` created and funded:
  `A0 = 500_000`, `B0 = 500_000`, `C0 = 500_000` paise. `Σ0 = 1_500_000`.
- One bearer token owning all three (or per-wallet tokens; transfers use each
  wallet's owner).

## Parameters

| Name | Default (local) | Default (deployed) | Meaning |
|------|-----------------|--------------------|---------|
| `ROUNDS` | 400 | 150 | total concurrent transfers |
| `AMT_MAX` | 10_000 | 10_000 | max random transfer amount (paise) |
| `MAX_INFLIGHT` | 64 | 16 | socket cap |

## Procedure

1. Record `A0,B0,C0`; assert `Σ0`.
2. Generate `ROUNDS` transfer specs: `from,to` picked from
   `{A→B, B→A, B→C, C→A, A→C, C→B}` uniformly; `amount_paise` uniform in
   `[1, AMT_MAX]`; **fresh `idempotency_key` each**. Ensure both A→B and B→A
   appear interleaved.
3. Fire all `ROUNDS` in parallel (bounded by `MAX_INFLIGHT`); `wait` for all.
4. Poll `GET /wallets/{A,B,C}` until two consecutive reads are stable (quiesce).
5. Compute `Σ_after = A + B + C`.
6. Scrape `/metrics`.

## Pass criteria

- [ ] `Σ_after == Σ0` **exactly** (integer equality, no tolerance).
- [ ] `A >= 0 && B >= 0 && C >= 0`.
- [ ] Every response is `201` (COMPLETED or DECLINED) — no `5xx`.
- [ ] `count(HTTP 500) == 0` and **no `40P01` / deadlock** in logs.
- [ ] `wallet_transfers_created_total` + `wallet_transfers_declined_total`
      increased by exactly `ROUNDS` combined.
- [ ] (local) mid-run sampling probe: no `GET /wallets` ever returns a negative
      balance.

## Fail signatures

- `Σ_after != Σ0` → money created or destroyed. Δ > 0: double credit / missing
  debit. Δ < 0: debit without credit (partial apply, or credit rolled back
  independently).
- Any negative balance → overdraft slipped through under the race.
- `40P01` in logs / request timeouts → ABBA deadlock; lock order not
  deterministic.
- `5xx` under load → unhandled contention (lock timeout, pool exhaustion).

## Artifacts captured

- `Σ0` and `Σ_after`, per-wallet before/after, the `ROUNDS` spec list, HTTP
  status histogram, `/metrics` delta, grep of logs for `ERROR`/`40P01`.
