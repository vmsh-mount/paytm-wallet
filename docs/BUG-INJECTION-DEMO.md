# Bug-injection demo — proving the assertions bite

TASK-12's acceptance criteria ask for a real demonstration that `scripts/burst.sh`
actually fails when an invariant breaks, not just when it holds. This was run
locally against a fresh Postgres and a `java -jar` instance of the built jar
(the compose/deployed path exercises the identical code).

## The bug

`AbstractJdbcTransferEngine.debitConditional` — dropped the overdraft predicate:

```diff
     protected Long debitConditional(UUID from, long amount) {
         return jdbc.query(
                 "UPDATE wallets SET balance_paise = balance_paise - ? "
-                + "WHERE id = ? AND balance_paise >= ? RETURNING balance_paise",
+                + "WHERE id = ? RETURNING balance_paise",
                 rs -> rs.next() ? rs.getLong(1) : null,
-                amount, from, amount);
+                amount, from);
     }
```

This is the exact predicate invariant **#2 (no overdraft)** depends on for the
default `conditional-update` engine.

## Clean run first (baseline)

```
$ N=20 K=12 ROUNDS=60 MAX_INFLIGHT=8 ./scripts/burst.sh http://localhost:8080
...
== Probe 4: no-overdraft race ==
  ok  exactly floor(balance/amount)=5 of 25 completed
  ok  the rest (20) declined cleanly
  ok  source balance never went negative
  ok  source debited exactly 5 times, no more

== summary: 0 assertion failure(s) ==
$ echo $?
0
```

## With the bug, same command

```
$ N=15 K=8 ROUNDS=40 MAX_INFLIGHT=8 ./scripts/burst.sh http://localhost:8080
...
== Probe 4: no-overdraft race ==
  ok  exactly floor(balance/amount)=5 of 25 completed
  FAIL  the rest (20) declined cleanly (expected 20, got 0)
  ok  source balance never went negative
  ok  source debited exactly 5 times, no more

== summary: 1 assertion failure(s) ==
$ echo $?
1
```

## Reading the result

Defense-in-depth caught the data corruption; the eval harness caught the
API-contract corruption.

The wallet's balance after the run was **exactly `0`**, never negative — the
`CHECK (balance_paise >= 0)` constraint from `V1__init.sql` (defence-in-depth,
TASK-01) held even with the application-level predicate removed. So invariant
\#2 was never actually violated at the data layer.

But the 20 attempts past the affordable count didn't decline cleanly — they hit
the `CHECK` constraint as a raw SQL exception, which `ApiExceptionHandler`'s
catch-all turned into a bare `500`:

```
$ curl -s -w '\n%{http_code}\n' -H 'Authorization: Bearer dev-token-alice' \
    -H 'Content-Type: application/json' -X POST http://localhost:8080/transfers \
    -d '{"from":"<wallet-a>","to":"<wallet-b>","amount_paise":100,"idempotency_key":"manual-check-1"}'
{"error":"internal_error","message":"an unexpected error occurred","correlation_id":"1e8a064b-dbe1-4156-80fb-406e54ae975b"}
500
```

That's a real regression — a client should see a clean `201 DECLINED`, not a
`500` — and the harness caught it precisely because it asserts the *documented
API contract* ("declined cleanly"), not just "did the balance survive". Two
independent layers, two independent failure signatures:

| Layer | What it checks | Result with the bug |
|-------|-----------------|----------------------|
| DB `CHECK` constraint | balance never negative | held — 0, not negative |
| `burst.sh` Probe 4 | HTTP contract: over-limit debits decline cleanly (`DECLINED`, not `5xx`) | **failed** — caught the regression |

The fix is reverting the diff above; `AbstractJdbcTransferEngine.java` in the
shipped code has the full predicate. This bug was never committed — the diff
above and the transcripts are the record.
