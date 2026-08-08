ALTER TABLE event_inbox
    ADD COLUMN subscriber_id VARCHAR(255);

-- Preserve the exact idempotency scope of known pre-migration messages. Unknown historical rows
-- remain globally claimed so replaying them after the migration cannot repeat a business effect.
UPDATE event_inbox
   SET subscriber_id = CASE
       WHEN event_type IN ('OrderPlacedIntegrationEvent', 'OrderCancelledIntegrationEvent')
           THEN 'allocation-ordering-events'
       WHEN event_type = 'StockAvailabilityIncreasedIntegrationEvent'
           THEN 'allocation-inventory-events'
       WHEN event_type IN ('OrderAllocatedIntegrationEvent', 'BackorderCreatedIntegrationEvent')
           THEN 'ordering-allocation-events'
       WHEN event_type = 'ConfirmStockReceiptRequest'
           THEN 'stock-receipt-requests'
       ELSE 'legacy-global'
       END;

ALTER TABLE event_inbox
    ALTER COLUMN subscriber_id SET NOT NULL;

ALTER TABLE event_inbox
    DROP CONSTRAINT event_inbox_pkey;

ALTER TABLE event_inbox
    ADD CONSTRAINT event_inbox_pkey PRIMARY KEY (subscriber_id, event_id);

CREATE INDEX idx_event_inbox_event_id ON event_inbox (event_id);
