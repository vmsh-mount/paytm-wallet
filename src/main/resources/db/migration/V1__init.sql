-- Scaffold schema. Refine during implementation.

CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid()

CREATE TABLE wallets (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       text NOT NULL,
    balance_paise bigint NOT NULL DEFAULT 0 CHECK (balance_paise >= 0),  -- no-overdraft, defense in depth
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT wallets_user_id_key UNIQUE (user_id)                      -- race-free get-or-create
);

CREATE TABLE transfers (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    from_wallet_id   uuid NOT NULL REFERENCES wallets(id),
    to_wallet_id     uuid NOT NULL REFERENCES wallets(id),
    amount_paise     bigint NOT NULL CHECK (amount_paise > 0),
    idempotency_key  text NOT NULL,
    request_fingerprint text NOT NULL,                                   -- hash of (from,to,amount) for same-key/different-body 409
    status           text NOT NULL CHECK (status IN ('CREATED','COMPLETED','DECLINED')),
    decline_reason   text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT transfers_idempotency_key_key UNIQUE (idempotency_key),   -- exactly-once, committed with the debit/credit
    CONSTRAINT transfers_distinct_wallets CHECK (from_wallet_id <> to_wallet_id)
);

CREATE INDEX transfers_from_wallet_idx ON transfers(from_wallet_id);
CREATE INDEX transfers_to_wallet_idx   ON transfers(to_wallet_id);
