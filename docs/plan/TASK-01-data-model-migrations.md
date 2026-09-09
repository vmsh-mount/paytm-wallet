# TASK-01 — Data model & migrations

| | |
|---|---|
| **Status** | Done |
| **Branch** | `task/01-data-model-migrations` |
| **Depends on** | TASK-00 |
| **Invariant(s)** | #1 #2 #3 #4 (schema-level enforcement) |
| **Est. effort** | ~2h |

## Context

Every invariant is anchored in the schema. The scaffold has a draft `V1__init.sql`;
this task finalises it, decides the balance-representation question, and adds the
read-only repository methods the API layer will call.

## Goal

A frozen `V1` migration whose constraints alone make three of the four invariants
*impossible to violate* (overdraft, duplicate wallet, duplicate idempotency key),
plus typed row mappers.

## Scope

### In scope
- Finalise `wallets` and `transfers` DDL
- **Decision: balance column vs append-only ledger** — document and implement one
- `CHECK (balance_paise >= 0)`, `UNIQUE(user_id)`, `UNIQUE(idempotency_key)`,
  `CHECK (amount_paise > 0)`, `CHECK (from_wallet_id <> to_wallet_id)`
- `request_fingerprint` column (for TASK-05's 409 path)
- `RowMapper<Wallet>`, `RowMapper<Transfer>`; read methods in both repositories
- Flyway config verified (runs on boot, fails fast on drift)
- Seed helper for tests only (not a migration)

### Out of scope
- Any write path / locking SQL (TASK-03, TASK-04)
- Idempotency insert logic (TASK-05)

## Design notes / decisions

- **Balance column as source of truth (chosen for R2).** A single
  `wallets.balance_paise` updated transactionally. Simpler to reason about, one
  row lock per wallet, trivially satisfies conservation if debit+credit share a
  tx.
  - *Rejected: double-entry ledger* (`entries(transfer_id, wallet_id, delta)` +
    balance = `SUM(delta)`). Auditable and the "real" bank design, but every
    balance read is an aggregate or needs a materialised snapshot, and
    no-overdraft becomes "sum for update" — more machinery than this exercise
    needs. Note it as the scale-up path.
- **`bigint` paise** — max ≈ 9.2×10¹⁸ paise; no realistic overflow. Reject
  `numeric` (invites float thinking, slower).
- **`request_fingerprint` = lowercase hex SHA-256 of `from|to|amount_paise`.**
  Stored so a same-key replay can be compared without trusting the client.
- FK `transfers.*_wallet_id → wallets.id` — a transfer can't reference a ghost
  wallet.

## Deliverables

- `src/main/resources/db/migration/V1__init.sql` (frozen)
- `repo/WalletRepository`: `findById`, `findByUserId`
- `repo/TransferRepository`: `findById`, `findByIdempotencyKey`
- `repo/RowMappers.java`
- `docs/WRITEUP.md` §"Data model" filled

## Acceptance criteria

- [ ] `flyway migrate` on an empty DB creates both tables + all constraints
- [ ] Inserting `balance_paise = -1` is rejected by the DB
- [ ] Two rows with the same `user_id` → unique violation
- [ ] Two rows with the same `idempotency_key` → unique violation
- [ ] `findById` / `findByUserId` / `findByIdempotencyKey` round-trip a row to the
      correct record type, `Instant` fields in UTC

## Test plan

### Unit
| Test | Asserts |
|------|---------|
| `RowMappersTest` | column → record field mapping, null `decline_reason` handled |

### Integration (`*IT` via Testcontainers)
| Test | Asserts | Engines |
|------|---------|---------|
| `SchemaIT.constraints_enforced` | each CHECK/UNIQUE rejects its violation | n/a |
| `WalletRepositoryIT.read_roundtrip` | insert then `findByUserId` returns equal record | n/a |
| `TransferRepositoryIT.read_roundtrip` | `findByIdempotencyKey` hit/miss | n/a |

### Eval scenarios
| Scenario | Runs against | Pass criteria |
|----------|--------------|---------------|
| — | — | (schema is covered by ITs; evals come with the write paths) |

## Definition of done

- [ ] Acceptance criteria met, ITs green
- [ ] `WRITEUP.md` §Data model complete incl. rejected ledger design
- [ ] `V1` marked frozen in a header comment; further changes go to `V2+`
- [ ] Branch squash-merged, status Done

## AI: directed vs decided

- **Directed**: balance-column-not-ledger decision, paise-as-bigint, fingerprint
  definition.
- **Decided**: exact index set, RowMapper style.
