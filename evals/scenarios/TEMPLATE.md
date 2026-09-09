# EVAL-Xn — <name>

| | |
|---|---|
| **Family** | Correctness \| Operational |
| **Invariant** | #n <name> \| — |
| **Owning task** | TASK-nn |
| **Runs against** | local compose \| deployed URL \| CI |
| **Status** | Draft \| Implemented \| Passing |

## Goal

One sentence: the property this scenario proves.

## Preconditions

- Fixtures to create (wallets, balances, tokens), stated as exact values.

## Parameters

| Name | Default (local) | Default (deployed) | Meaning |
|------|-----------------|--------------------|---------|
| `N` | 50 | 30 | ... |

## Procedure

1. ...
2. ... (make the concurrency explicit: "fire X in parallel, `wait`")
3. ...

## Pass criteria

- [ ] Exact, checkable assertions. Computed value vs expected.

## Fail signatures

What a violation looks like — so a real failure is recognizable and not
hand-waved as flakiness.

- e.g. `SUM(balances) != Σ₀` → money created/destroyed
- e.g. two wallet rows for one user → get-or-create race
- e.g. HTTP 500 with `40P01` in logs → deadlock

## Artifacts captured

- request/response transcripts, `/metrics` delta, log excerpt (correlation ids),
  final balance dump.
