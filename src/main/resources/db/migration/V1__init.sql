-- ============================================================================
--  V1 — FROZEN.  Do not edit after merge.
--  Flyway validates checksums on boot; any edit to an applied migration fails
--  fast. Further schema changes go to V2__*.sql.
--
--  Invariants anchored here:
--    #1 Conservation      — FKs guarantee both wallets exist; debit+credit share
--                           one tx (enforced in the service/engine layer).
--    #2 No overdraft      — CHECK (balance_paise >= 0)          [defence in depth]
--    #3 Exactly-once      — UNIQUE (idempotency_key), committed with debit+credit
--    #4 Race-free wallet  — UNIQUE (user_id)
--
--  Money is integer paise (bigint, max ~9.2e18). No NUMERIC, no floats.
--  Balance column is the source of truth (not a double-entry ledger) — see
--  docs/WRITEUP.md §"Data model" for the rejected alternative and scale path.
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid()

CREATE TABLE wallets (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       text   NOT NULL,
    balance_paise bigint NOT NULL DEFAULT 0 CHECK (balance_paise >= 0),  -- #2
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT wallets_user_id_key UNIQUE (user_id)                      -- #4
);

CREATE TABLE transfers (
    id                  uuid   PRIMARY KEY DEFAULT gen_random_uuid(),
    from_wallet_id      uuid   NOT NULL REFERENCES wallets(id),
    to_wallet_id        uuid   NOT NULL REFERENCES wallets(id),
    amount_paise        bigint NOT NULL CHECK (amount_paise > 0),
    idempotency_key     text   NOT NULL,
    request_fingerprint text   NOT NULL,                                 -- lowercase hex SHA-256 of "from|to|amount_paise"
    status              text   NOT NULL CHECK (status IN ('CREATED','COMPLETED','DECLINED')),
    decline_reason      text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT transfers_idempotency_key_key UNIQUE (idempotency_key),   -- #3
    CONSTRAINT transfers_distinct_wallets   CHECK (from_wallet_id <> to_wallet_id)
);

CREATE INDEX transfers_from_wallet_idx ON transfers (from_wallet_id);
CREATE INDEX transfers_to_wallet_idx   ON transfers (to_wallet_id);
