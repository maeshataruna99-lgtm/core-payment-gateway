-- What: Creates provider callback records, the outbox, and webhook endpoints and deliveries.
-- Why: Phase 4 needs replay protection and reliable, observable webhook delivery.
-- How to use: Write outbox rows in the same transaction as the state change they announce.
--             Webhook secrets are stored encrypted, because signing needs the original value.
-- Data: It adds empty tables and changes no existing data.
-- Testing: ProviderCallbackIntegrationTest, WebhookIntegrationTest.

-- The primary key is what rejects a replayed provider event, even under concurrency.
CREATE TABLE provider_callback_events (
    provider    text NOT NULL,
    event_id    text NOT NULL,
    received_at timestamptz NOT NULL,
    PRIMARY KEY (provider, event_id)
);

CREATE TABLE outbox_events (
    id           uuid PRIMARY KEY,
    type         text NOT NULL,
    merchant_id  uuid NOT NULL REFERENCES merchants (id),
    payload      jsonb NOT NULL,
    created_at   timestamptz NOT NULL,
    published_at timestamptz
);

CREATE INDEX outbox_events_unpublished_idx ON outbox_events (created_at)
    WHERE published_at IS NULL;

CREATE TABLE webhook_endpoints (
    id               uuid PRIMARY KEY,
    merchant_id      uuid NOT NULL REFERENCES merchants (id),
    url              text NOT NULL,
    secret_encrypted text NOT NULL,
    active           boolean NOT NULL,
    created_at       timestamptz NOT NULL
);

CREATE INDEX webhook_endpoints_merchant_idx ON webhook_endpoints (merchant_id);

CREATE TABLE webhook_endpoint_events (
    endpoint_id uuid NOT NULL REFERENCES webhook_endpoints (id),
    event_type  text NOT NULL,
    PRIMARY KEY (endpoint_id, event_type)
);

CREATE TABLE webhook_deliveries (
    id              uuid PRIMARY KEY,
    endpoint_id     uuid NOT NULL REFERENCES webhook_endpoints (id),
    outbox_event_id uuid NOT NULL REFERENCES outbox_events (id),
    merchant_id     uuid NOT NULL REFERENCES merchants (id),
    event_type      text NOT NULL,
    -- The exact bytes that are signed and sent.
    payload         text NOT NULL,
    state           text NOT NULL CHECK (state IN ('PENDING', 'DELIVERED', 'FAILED', 'CANCELLED')),
    attempts        integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL,
    created_at      timestamptz NOT NULL,
    delivered_at    timestamptz,
    UNIQUE (endpoint_id, outbox_event_id)
);

CREATE INDEX webhook_deliveries_due_idx ON webhook_deliveries (next_attempt_at)
    WHERE state = 'PENDING';
CREATE INDEX webhook_deliveries_merchant_idx ON webhook_deliveries (merchant_id, created_at);

CREATE TABLE webhook_delivery_attempts (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    delivery_id uuid NOT NULL REFERENCES webhook_deliveries (id),
    attempt     integer NOT NULL,
    status_code integer,
    error       text,
    created_at  timestamptz NOT NULL
);

CREATE INDEX webhook_delivery_attempts_delivery_idx ON webhook_delivery_attempts (delivery_id, id);
