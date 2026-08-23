CREATE TABLE applications (
    id UUID PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    request_hash CHAR(64) NOT NULL,
    applicant_reference VARCHAR(64) NOT NULL,
    credit_score INTEGER NOT NULL CHECK (credit_score BETWEEN 300 AND 850),
    annual_income NUMERIC(14, 2) NOT NULL CHECK (annual_income > 0),
    requested_amount NUMERIC(14, 2) NOT NULL CHECK (requested_amount > 0),
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL REFERENCES applications(id),
    event_type VARCHAR(80) NOT NULL,
    payload JSONB NOT NULL,
    traceparent VARCHAR(128) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    publish_attempts INTEGER NOT NULL DEFAULT 0,
    lock_token UUID,
    locked_until TIMESTAMPTZ,
    last_error VARCHAR(500)
);

CREATE INDEX idx_outbox_pending
    ON outbox_events (created_at)
    WHERE published_at IS NULL;

CREATE TABLE audit_records (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    application_id UUID NOT NULL REFERENCES applications(id),
    event_id UUID,
    type VARCHAR(80) NOT NULL,
    details JSONB NOT NULL,
    trace_id VARCHAR(32) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL
);

CREATE OR REPLACE FUNCTION reject_audit_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit_records are append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_records_append_only
    BEFORE UPDATE OR DELETE ON audit_records
    FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();
