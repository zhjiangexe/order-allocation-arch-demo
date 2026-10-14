-- 事件基礎設施；Inbox 以 subscriber + event 識別消費，Outbox 保存通用 headers。

CREATE TABLE event_inbox (
    event_id UUID NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    subscriber_id VARCHAR(255) NOT NULL,
    CONSTRAINT event_inbox_pkey PRIMARY KEY (subscriber_id, event_id)
);

CREATE INDEX idx_event_inbox_event_id
    ON event_inbox (event_id);

CREATE TABLE event_outbox (
    id UUID NOT NULL,
    aggregatetype VARCHAR(255) NOT NULL,
    aggregateid VARCHAR(255) NOT NULL,
    type VARCHAR(255) NOT NULL,
    route VARCHAR(255) NOT NULL,
    partition_key VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,
    "timestamp" TIMESTAMPTZ NOT NULL,
    headers TEXT DEFAULT '{}'::TEXT NOT NULL,
    CONSTRAINT event_outbox_pkey PRIMARY KEY (id)
);

CREATE INDEX idx_event_outbox_timestamp
    ON event_outbox ("timestamp");
