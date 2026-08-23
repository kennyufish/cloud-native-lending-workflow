ALTER TABLE applications
    ADD COLUMN offer_apr NUMERIC(5, 2),
    ADD COLUMN offer_term_months INTEGER,
    ADD COLUMN decline_reason VARCHAR(120),
    ADD COLUMN notification_status VARCHAR(32);

CREATE TABLE processed_events (
    event_id UUID PRIMARY KEY,
    application_id UUID NOT NULL REFERENCES applications(id),
    decision_hash CHAR(64) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL
);
