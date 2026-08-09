-- Serialized generic Message headers relayed by Debezium as the messageHeaders Kafka header.
-- The default keeps rows written by the rolling-deployment JPA producer backward compatible.
ALTER TABLE event_outbox
    ADD COLUMN headers TEXT NOT NULL DEFAULT '{}';
