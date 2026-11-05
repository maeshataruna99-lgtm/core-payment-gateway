-- What: Creates reconciliation runs and their discrepancies, and indexes payments for matching.
-- Why: Reconciliation compares internal payments with a provider's settlement file.
-- How to use: Each run is a permanent record. A discrepancy is for review and changes nothing else.
-- Data: It adds empty tables and indexes, and changes no existing data.
-- Testing: ReconciliationApiIntegrationTest.

CREATE INDEX payment_attempts_merchant_reference_idx
    ON payment_attempts (merchant_id, provider_reference);
CREATE INDEX payment_attempts_merchant_state_updated_idx
    ON payment_attempts (merchant_id, state, updated_at);

CREATE TABLE reconciliation_runs (
    id            uuid PRIMARY KEY,
    merchant_id   uuid NOT NULL REFERENCES merchants (id),
    period_from   timestamptz NOT NULL,
    period_to     timestamptz NOT NULL,
    file_sha256   char(64) NOT NULL,
    row_count     integer NOT NULL,
    matched_count integer NOT NULL,
    actor         text NOT NULL,
    created_at    timestamptz NOT NULL,
    CHECK (period_from < period_to)
);

CREATE INDEX reconciliation_runs_merchant_idx ON reconciliation_runs (merchant_id, created_at);

CREATE TABLE reconciliation_discrepancies (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id             uuid NOT NULL REFERENCES reconciliation_runs (id),
    type               text NOT NULL CHECK (type IN (
        'MISSING_IN_PROVIDER', 'MISSING_INTERNALLY', 'DUPLICATE',
        'AMOUNT_MISMATCH', 'CURRENCY_MISMATCH', 'STATUS_MISMATCH', 'DATE_MISMATCH')),
    provider_reference text,
    payment_id         uuid,
    internal_value     text,
    provider_value     text
);

CREATE INDEX reconciliation_discrepancies_run_idx ON reconciliation_discrepancies (run_id, id);

CREATE TRIGGER reconciliation_runs_immutable BEFORE UPDATE OR DELETE ON reconciliation_runs
    FOR EACH ROW EXECUTE FUNCTION reject_change();
CREATE TRIGGER reconciliation_discrepancies_immutable
    BEFORE UPDATE OR DELETE ON reconciliation_discrepancies
    FOR EACH ROW EXECUTE FUNCTION reject_change();
