-- Canonical operation and line identities. Nullable all-at-once semantics preserve source-agnostic
-- inbound records while every stock-consuming outbound group is complete.

ALTER TABLE stock_pickings
    ADD CONSTRAINT ck_stock_pickings_source_identity CHECK (
        (direction = 'OUTBOUND'
         AND source_type IS NOT NULL
         AND btrim(source_id) <> ''
         AND btrim(allocation_unit_key) <> ''
         AND policy_code = 'SHIP_COMPLETE'
         AND enqueued_at IS NOT NULL)
        OR
        (direction IS DISTINCT FROM 'OUTBOUND'
         AND source_type IS NULL
         AND source_id IS NULL
         AND allocation_unit_key IS NULL
         AND policy_code IS NULL
         AND enqueued_at IS NULL)
    ),
    ADD CONSTRAINT ck_stock_pickings_source_type CHECK (
        source_type IS NULL
        OR source_type IN ('ORDER', 'TRANSFER', 'REPLENISHMENT', 'PRODUCTION', 'MANUAL')
    );

CREATE UNIQUE INDEX uq_stock_pickings_source_unit
    ON stock_pickings (source_type, source_id, allocation_unit_key)
    WHERE source_type IS NOT NULL;

CREATE INDEX idx_stock_pickings_confirmed_scope_queue
    ON stock_pickings (owner_id, from_location_id, enqueued_at, id)
    WHERE state = 'CONFIRMED' AND direction = 'OUTBOUND';

ALTER TABLE stock_moves
    ADD CONSTRAINT ck_stock_moves_source_line_identity CHECK (
        (source_line_id IS NULL AND line_sequence IS NULL)
        OR (btrim(source_line_id) <> '' AND line_sequence > 0)
    );

CREATE UNIQUE INDEX uq_stock_moves_picking_source_line
    ON stock_moves (picking_id, source_line_id)
    WHERE source_line_id IS NOT NULL;

CREATE UNIQUE INDEX uq_stock_moves_picking_line_sequence
    ON stock_moves (picking_id, line_sequence)
    WHERE line_sequence IS NOT NULL;

CREATE INDEX idx_stock_moves_picking_ordered
    ON stock_moves (picking_id, line_sequence, id);

CREATE INDEX idx_stock_moves_confirmed_scope_sku
    ON stock_moves (owner_id, from_location_id, sku_code, picking_id)
    WHERE state = 'CONFIRMED';

CREATE INDEX idx_stock_moves_confirmed_picking_sku
    ON stock_moves (picking_id, sku_code)
    WHERE state = 'CONFIRMED';
