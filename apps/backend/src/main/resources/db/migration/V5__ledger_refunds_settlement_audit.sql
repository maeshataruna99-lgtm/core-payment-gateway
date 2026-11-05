-- What: Creates the double-entry ledger, refunds, settlement batches, and the audit log.
-- Why: Phase 5 needs money records that stay balanced and cannot be edited.
-- How to use: Post through LedgerService. Corrections are new entries, never updates.
-- Data: It adds empty tables and triggers. Payments that succeeded before this migration have no
--       ledger entries, so they are not settled and cannot be refunded.
-- Testing: LedgerIntegrationTest, RefundApiIntegrationTest, SettlementApiIntegrationTest,
--          AuditLogIntegrationTest.

CREATE FUNCTION reject_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% rows cannot be changed', TG_TABLE_NAME;
END;
$$;

CREATE TABLE ledger_transactions (
    id           uuid PRIMARY KEY,
    merchant_id  uuid NOT NULL REFERENCES merchants (id),
    type         text NOT NULL CHECK (type IN
        ('PAYMENT', 'UNAPPLIED_PAYMENT', 'REFUND', 'UNAPPLIED_REFUND', 'SETTLEMENT')),
    reference_id uuid NOT NULL,
    -- For a refund, the payment it belongs to.
    related_id   uuid,
    created_at   timestamptz NOT NULL,
    -- One money movement can never be posted twice.
    UNIQUE (type, reference_id)
);

CREATE INDEX ledger_transactions_merchant_idx ON ledger_transactions (merchant_id, created_at);

CREATE TABLE ledger_entries (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transaction_id uuid NOT NULL REFERENCES ledger_transactions (id),
    account        text NOT NULL CHECK (account IN
        ('provider_clearing', 'merchant_payable', 'fee_revenue', 'unapplied_funds')),
    direction      text NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount         bigint NOT NULL CHECK (amount > 0)
);

CREATE INDEX ledger_entries_transaction_idx ON ledger_entries (transaction_id);

-- Runs at commit, so a transaction may add its entries one by one.
CREATE FUNCTION ledger_check_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    difference bigint;
BEGIN
    SELECT coalesce(sum(CASE direction WHEN 'DEBIT' THEN amount ELSE -amount END), 0)
    INTO difference FROM ledger_entries WHERE transaction_id = NEW.transaction_id;
    IF difference <> 0 THEN
        RAISE EXCEPTION 'ledger transaction % is not balanced', NEW.transaction_id;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ledger_entries_balanced AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ledger_check_balanced();

CREATE TRIGGER ledger_transactions_append_only BEFORE UPDATE OR DELETE ON ledger_transactions
    FOR EACH ROW EXECUTE FUNCTION reject_change();
CREATE TRIGGER ledger_entries_append_only BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION reject_change();

CREATE TABLE refunds (
    id         uuid PRIMARY KEY,
    payment_id uuid NOT NULL REFERENCES payment_attempts (id),
    merchant_id uuid NOT NULL REFERENCES merchants (id),
    method     text NOT NULL,
    amount     bigint NOT NULL CHECK (amount > 0),
    currency   text NOT NULL CHECK (currency = 'IDR'),
    reason     text NOT NULL,
    state      text NOT NULL CHECK (state IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    actor      text NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE INDEX refunds_payment_idx ON refunds (payment_id, created_at);
CREATE INDEX refunds_pending_idx ON refunds (created_at) WHERE state = 'PENDING';

CREATE TABLE settlement_batches (
    id           uuid PRIMARY KEY,
    merchant_id  uuid NOT NULL REFERENCES merchants (id),
    gross        bigint NOT NULL,
    fees         bigint NOT NULL,
    refunds      bigint NOT NULL,
    adjustments  bigint NOT NULL,
    net          bigint NOT NULL CHECK (net > 0),
    actor        text NOT NULL,
    finalized_at timestamptz NOT NULL,
    CHECK (net = gross - fees - refunds + adjustments)
);

CREATE INDEX settlement_batches_merchant_idx ON settlement_batches (merchant_id, finalized_at);

-- The primary key is what stops a ledger transaction from being settled twice.
CREATE TABLE ledger_settlement_marks (
    transaction_id uuid PRIMARY KEY REFERENCES ledger_transactions (id),
    batch_id       uuid NOT NULL REFERENCES settlement_batches (id)
);

CREATE INDEX ledger_settlement_marks_batch_idx ON ledger_settlement_marks (batch_id);

CREATE TRIGGER settlement_batches_immutable BEFORE UPDATE OR DELETE ON settlement_batches
    FOR EACH ROW EXECUTE FUNCTION reject_change();
CREATE TRIGGER ledger_settlement_marks_immutable BEFORE UPDATE OR DELETE ON ledger_settlement_marks
    FOR EACH ROW EXECUTE FUNCTION reject_change();

-- Each row's hash covers the previous row's hash, so an edit breaks the chain from there on.
CREATE TABLE audit_events (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor          text NOT NULL,
    action         text NOT NULL,
    resource_type  text NOT NULL,
    resource_id    text NOT NULL,
    correlation_id text,
    created_at     timestamptz NOT NULL,
    previous_hash  char(64) NOT NULL,
    hash           char(64) NOT NULL
);

CREATE TRIGGER audit_events_append_only BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION reject_change();
