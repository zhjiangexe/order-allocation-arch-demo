-- Historical payloads remain untouched. This migration changes only the current WMS idempotency
-- key from the legacy wire allocation ID to canonical Inventory picking identity.

ALTER TABLE wms_shipments
    DROP CONSTRAINT uq_wms_shipments_allocation,
    DROP COLUMN allocation_id,
    ALTER COLUMN picking_id SET NOT NULL,
    ADD CONSTRAINT uq_wms_shipments_picking UNIQUE (picking_id);

DROP INDEX idx_wms_shipments_picking_expand;
