-- Expand-only move-centric schema. Existing demand-based binaries can continue to read and write
-- while nullable canonical columns are populated by the following deterministic backfills.

ALTER TABLE stock_pickings
    ADD COLUMN source_type VARCHAR(32),
    ADD COLUMN source_id VARCHAR(255),
    ADD COLUMN allocation_unit_key VARCHAR(128),
    ADD COLUMN policy_code VARCHAR(64),
    ADD COLUMN enqueued_at TIMESTAMPTZ,
    ADD COLUMN legacy_allocation_demand_id UUID;

ALTER TABLE stock_moves
    ADD COLUMN line_sequence INTEGER;

ALTER TABLE allocation_cancellation_operations
    ADD COLUMN picking_id UUID;

ALTER TABLE wms_shipments
    ADD COLUMN picking_id UUID;

CREATE UNIQUE INDEX uq_stock_pickings_legacy_allocation_demand
    ON stock_pickings (legacy_allocation_demand_id)
    WHERE legacy_allocation_demand_id IS NOT NULL;

CREATE INDEX idx_allocation_cancellation_operations_picking
    ON allocation_cancellation_operations (picking_id, operation_id)
    WHERE picking_id IS NOT NULL;

CREATE INDEX idx_wms_shipments_picking_expand
    ON wms_shipments (picking_id)
    WHERE picking_id IS NOT NULL;
