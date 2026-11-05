-- What: Creates payment attempts, their event history, and payment discrepancies.
-- Why: Phase 3 needs a validated, auditable payment lifecycle.
-- How to use: Change attempt state only through PaymentEventService. Amounts are whole rupiah.
-- Data: It adds a nullable column to checkout_sessions and changes no existing rows.
-- Testing: PaymentApiIntegrationTest.

CREATE TABLE payment_attempts (
    id                 uuid PRIMARY KEY,
    checkout_id        uuid NOT NULL REFERENCES checkout_sessions (id),
    merchant_id        uuid NOT NULL REFERENCES merchants (id),
    method             text NOT NULL CHECK (method IN ('QRIS', 'VIRTUAL_ACCOUNT')),
    channel_code       text,
    amount             bigint NOT NULL CHECK (amount > 0),
    currency           text NOT NULL CHECK (currency = 'IDR'),
    state              text NOT NULL
        CHECK (state IN ('CREATED', 'PENDING', 'UNKNOWN', 'SUCCEEDED', 'FAILED', 'EXPIRED')),
    provider_reference text,
    instruction_label  text,
    instruction_value  text,
    -- The method the provider reports, which can differ from the one requested.
    paid_method        text,
    expires_at         timestamptz NOT NULL,
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL
);

CREATE INDEX payment_attempts_checkout_idx ON payment_attempts (checkout_id, created_at);
-- Only attempts the sweeper still has to look at.
CREATE INDEX payment_attempts_open_idx ON payment_attempts (state, expires_at)
    WHERE state IN ('CREATED', 'PENDING', 'UNKNOWN');

-- The first success settles a checkout. The update that sets this column is conditional.
ALTER TABLE checkout_sessions
    ADD COLUMN paid_attempt_id uuid REFERENCES payment_attempts (id);

CREATE TABLE payment_attempt_events (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    attempt_id  uuid NOT NULL REFERENCES payment_attempts (id),
    event       text NOT NULL,
    from_state  text NOT NULL,
    to_state    text NOT NULL,
    actor       text NOT NULL,
    created_at  timestamptz NOT NULL
);

CREATE INDEX payment_attempt_events_attempt_idx ON payment_attempt_events (attempt_id, id);

CREATE TABLE payment_discrepancies (
    id         uuid PRIMARY KEY,
    attempt_id uuid NOT NULL REFERENCES payment_attempts (id),
    type       text NOT NULL CHECK (type IN ('CONTRADICTORY_EVENT', 'DOUBLE_PAYMENT')),
    detail     text NOT NULL,
    created_at timestamptz NOT NULL
);

CREATE INDEX payment_discrepancies_attempt_idx ON payment_discrepancies (attempt_id);
