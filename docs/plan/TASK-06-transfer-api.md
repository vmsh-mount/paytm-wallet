# TASK-06 — Transfer API, status & error contract

| | |
|---|---|
| **Status** | Done |
| **Branch** | `task/06-transfer-api` |
| **Depends on** | TASK-04, TASK-05 |
| **Invariant(s)** | #1 #2 #3 (surface) |
| **Est. effort** | ~2.5h |

## Context

Wire the engine + idempotency into a clean, documented HTTP contract:
`POST /transfers`, `GET /transfers/{id}`, deterministic status codes, a single
error body shape, and the caller-owns-source check.

## Goal

Fully specified, tested HTTP API for transfers with an unambiguous status-code
table; `openapi.yaml` (or a README table) is authoritative.

## Scope

### In scope
- `POST /transfers` request validation (`@Valid`): `from`,`to` UUID non-null,
  `amount_paise` > 0, `idempotency_key` non-blank, length ≤ 200
- Ownership: caller `userId` (TASK-02) must own `from` → else `403`
- `GET /transfers/{id}` → status projection; `404` unknown
- **Status-code contract** (below), documented
- Uniform error body `{error, message, correlation_id}`
- `api/ApiExceptionHandler` fully implemented
- `docs/openapi.yaml` + README API table
- Domain event `transfer.request.received` with sanitized fields

### Out of scope
- Listing transfers, pagination, webhooks
- Multi-currency

## Design notes / decisions

- **Status codes:**
  | Case | Code | Body |
  |------|------|------|
  | transfer completed (new) | `201` | `TransferResponse{status:COMPLETED}` |
  | transfer **declined** (insufficient funds) | `201` | `TransferResponse{status:DECLINED, decline_reason}` |
  | idempotent replay (same key+body) | `200` | stored `TransferResponse` (COMPLETED or DECLINED) |
  | same key, different body | `409` | error |
  | validation failure | `400` | error |
  | caller not owner of `from` | `403` | error |
  | `from`/`to` wallet unknown | `404` | error |
  | unexpected | `500` | error (correlation id only, no stack) |
  - **Declined ≠ error.** A declined transfer is a *successful* API call that
    reports a business outcome; `4xx` would tell clients to fix their request,
    which is wrong. Documented — reviewers often probe this.
  - `201` for new (completed or declined), `200` for replay — lets a client tell
    "my write happened now" from "this was already processed".
- **`GET` returns the same `TransferResponse`** shape as `POST` for consistency.
- Ownership check in `TransferService`, not the controller or filter — it needs
  the wallet row.

## Deliverables

- `api/TransferController`, `api/ApiExceptionHandler` (impl)
- `api/Dtos` finalized
- `docs/openapi.yaml`
- README "API" section updated with the status table
- `docs/WRITEUP.md` — no new section, but §mechanism/§idempotency get the
  status-code rationale appended

## Acceptance criteria

- [ ] Every row of the status-code table has a passing MockMvc test
- [ ] `403` when Bob transfers from Alice's wallet
- [ ] Error bodies always include the request's correlation id (matches response
      header `X-Correlation-Id`)
- [ ] `openapi.yaml` validates (spectral / swagger-cli) and matches actual
      responses
- [ ] `GET /transfers/{id}` reflects `COMPLETED` / `DECLINED` correctly

## Test plan

### Unit
| Test | Asserts |
|------|---------|
| `TransferControllerTest` (MockMvc) | full status-code matrix, error body shape, validation messages |
| `ApiExceptionHandlerTest` | each exception → code + body, correlation id present |

### Integration
| Test | Asserts | Engines |
|------|---------|---------|
| `TransferApiIT` | end-to-end create → get; declined path; 409 path; 403 path | conditional |

### Eval scenarios
| Scenario | Runs against | Pass criteria |
|----------|--------------|---------------|
| `EVAL-O5` | deployed | scripted walk of the status table returns documented codes |

## Definition of done

- [ ] Status table fully tested and documented in README + `openapi.yaml`
- [ ] Acceptance criteria met
- [ ] Branch squash-merged, status Done

## AI: directed vs decided

- **Directed**: declined-is-201 semantics, 200-vs-201 replay distinction,
  ownership-in-service.
- **Decided**: OpenAPI authoring, exact validation error message text.
