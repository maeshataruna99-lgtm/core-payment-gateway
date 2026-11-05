-- What: Creates merchants, API clients, checkout sessions, and idempotency records.
-- Why: Phase 2 needs merchant-scoped authentication and safe checkout creation.
-- How to use: API keys are stored only as SHA-256 hashes. Amounts are whole rupiah.
-- Data: It adds empty tables and changes no existing data.
-- Testing: CheckoutApiIntegrationTest.

CREATE TABLE merchants (
    id         uuid PRIMARY KEY,
    name       text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE api_clients (
    id          uuid PRIMARY KEY,
    merchant_id uuid REFERENCES merchants (id),
    role        text NOT NULL CHECK (role IN ('MERCHANT', 'OPERATOR', 'ADMIN')),
    key_hash    char(64) NOT NULL UNIQUE,
    created_at  timestamptz NOT NULL DEFAULT now(),
    -- A merchant client always has a merchant. Staff clients never do.
    CHECK ((role = 'MERCHANT') = (merchant_id IS NOT NULL))
);

CREATE TABLE checkout_sessions (
    id                 uuid PRIMARY KEY,
    merchant_id        uuid NOT NULL REFERENCES merchants (id),
    merchant_order_id  text NOT NULL,
    amount             bigint NOT NULL CHECK (amount > 0),
    currency           text NOT NULL CHECK (currency = 'IDR'),
    description        text,
    customer_reference text,
    metadata           jsonb NOT NULL DEFAULT '{}',
    expires_at         timestamptz NOT NULL,
    created_at         timestamptz NOT NULL
);

CREATE INDEX checkout_sessions_merchant_idx ON checkout_sessions (merchant_id, created_at);

-- The primary key is what makes a repeated request safe under concurrency.
CREATE TABLE idempotency_keys (
    merchant_id     uuid NOT NULL REFERENCES merchants (id),
    idempotency_key text NOT NULL,
    request_hash    char(64) NOT NULL,
    resource_id     uuid NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (merchant_id, idempotency_key)
);
