CREATE TABLE ${messagingSchema}.${inboxTable} (
    subscriber_id VARCHAR(255) NOT NULL,
    event_id UUID NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (subscriber_id, event_id)
);

CREATE INDEX idx_event_inbox_event_id
    ON ${messagingSchema}.${inboxTable} (event_id);

CREATE TABLE ${messagingSchema}.${outboxTable} (
    id UUID PRIMARY KEY,
    aggregatetype VARCHAR(255) NOT NULL,
    aggregateid VARCHAR(255) NOT NULL,
    type VARCHAR(255) NOT NULL,
    route VARCHAR(255) NOT NULL,
    partition_key VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,
    timestamp TIMESTAMPTZ NOT NULL,
    headers TEXT NOT NULL DEFAULT '{}'
);

CREATE INDEX idx_event_outbox_timestamp
    ON ${messagingSchema}.${outboxTable} (timestamp);
