# TASK-02 — Bearer-token auth & request context

| | |
|---|---|
| **Status** | Not started |
| **Branch** | `task/02-bearer-auth` |
| **Depends on** | TASK-00 |
| **Invariant(s)** | — (but enables the ownership check in TASK-06) |
| **Est. effort** | ~1.5h |

## Context

"Auth sophistication is explicitly NOT graded" — but a call must be attributable
to a user so `POST /transfers` can reject a caller debiting a wallet they don't
own, and so logs carry `user_id`. Keep it minimal and obviously correct.

## Goal

`Authorization: Bearer <token>` resolves to a `userId` available to controllers
and the log MDC; unknown/missing token → `401` on business endpoints; actuator
and health stay open.

## Scope

### In scope
- `config/AuthFilter` — parse header, resolve token → `userId` from
  `wallet.auth.tokens` (`token:userId,token:userId`)
- `RequestContext` (request-scoped or `ThreadLocal` via filter) exposing `userId`
  and `correlationId`
- `401` JSON body `{error,message,correlation_id}` for bad/missing token
- Path allowlist: `/actuator/**`, `/healthz` (if added), OpenAPI if present
- Constant-time token compare; tokens never logged (log `userId` only)
- Filter order: `CorrelationIdFilter` (MIN_VALUE) → `AuthFilter` (MIN_VALUE+10)

### Out of scope
- Token issuance, refresh, expiry, scopes, JWT
- Rate limiting

## Design notes / decisions

- **Static config map, not a DB table.** Tokens are test fixtures; a table adds a
  migration and a lookup per request for zero grading value. `AUTH_TOKENS` is a
  `sync:false` secret in Render.
- **Servlet `Filter`, not Spring Security.** Spring Security pulls a large
  autoconfig surface and filter chain to express "map a header to a string". A
  30-line filter is easier to defend line-by-line.
  - *Rejected: Spring Security* — reconsider only if the reviewer wants method
    security; noted in WRITEUP.
- Ownership enforcement (caller `userId` must own `from` wallet) lives in
  `TransferService` (TASK-06), not the filter — the filter only authenticates.

## Deliverables

- `config/AuthFilter`, `config/RequestContext`, `config/AuthProperties`
- `api` error path for `401`
- `docs/WRITEUP.md` §"Auth" (2–3 lines: what and why-minimal)

## Acceptance criteria

- [ ] Valid token → request proceeds, `RequestContext.userId()` populated
- [ ] Missing / malformed / unknown token → `401` + JSON error + correlation id
- [ ] `GET /actuator/health` works with no `Authorization` header
- [ ] Token value never appears in logs at any level
- [ ] Two tokens mapping to different users are isolated

## Test plan

### Unit
| Test | Asserts |
|------|---------|
| `AuthPropertiesTest` | parses `t1:u1,t2:u2`; rejects malformed entry at startup |
| `AuthFilterTest` (MockMvc) | 200 with good token, 401 matrix, allowlist bypass |

### Integration
| Test | Asserts | Engines |
|------|---------|---------|
| `AuthIT` | end-to-end 401 on `POST /wallets` without token | n/a |

### Eval scenarios
| Scenario | Runs against | Pass criteria |
|----------|--------------|---------------|
| `EVAL-O5` (smoke) | deployed | unauthenticated `/actuator/health` = 200; unauthenticated `/wallets` = 401 |

## Definition of done

- [ ] Acceptance criteria met, tests green
- [ ] `WRITEUP.md` §Auth updated
- [ ] Branch squash-merged, status Done

## AI: directed vs decided

- **Directed**: filter-not-Spring-Security, static token map, allowlist.
- **Decided**: `RequestContext` implementation style, error JSON shape (shared
  with TASK-06).
