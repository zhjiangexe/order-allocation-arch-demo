-- Contract phase: application writers now address canonical picking and move identities. Historical
-- event payloads are retained, but the duplicate demand persistence model is removed.

DROP VIEW allocation_order_source_demands;
DROP VIEW demand_lines;

-- Source acceptance still needs a stable, read-only Ordering projection. Its name and shape describe
-- movement registration rather than reviving the removed allocation-demand lifecycle.
CREATE VIEW stock_order_movement_sources AS
SELECT o.id AS source_id,
       o.owner_id,
       o.facility_id,
       pt.id AS picking_type_id,
       pt.default_from_location_id AS source_location_id,
       pt.default_to_location_id AS destination_location_id,
       o.dispatch_by AS required_by,
       o.release_priority,
       o.received_at AS enqueued_at,
       ol.id AS source_line_reference_id,
       ol.id::text AS source_line_id,
       ol.sku_code,
       ol.quantity
  FROM orders o
  JOIN order_lines ol ON ol.order_id = o.id
  LEFT JOIN stock_picking_types pt
    ON pt.facility_id = o.facility_id
   AND pt.code = 'OUTBOUND'
 WHERE o.cancelled_at IS NULL;

DROP INDEX idx_stock_moves_waiting;
DROP INDEX idx_stock_moves_order_line;
DROP INDEX uq_stock_moves_allocation_demand_line;

ALTER TABLE stock_moves
    DROP CONSTRAINT ck_stock_moves_allocation_reference_pair,
    DROP CONSTRAINT ck_stock_moves_order_demand_reference,
    DROP CONSTRAINT fk_stock_moves_allocation_demand,
    DROP CONSTRAINT fk_stock_moves_allocation_demand_line,
    DROP CONSTRAINT fk_stock_moves_order_line,
    DROP COLUMN allocation_demand_id,
    DROP COLUMN allocation_demand_line_id,
    DROP COLUMN order_line_id;

-- Intermediate source-only writers could have left an empty outbound shell. It has neither stock
-- intent nor execution evidence and must not survive as a canonical operation group.
DELETE FROM stock_pickings picking
 WHERE picking.direction = 'OUTBOUND'
   AND picking.legacy_allocation_demand_id IS NULL
   AND NOT EXISTS (
       SELECT 1
         FROM stock_moves move
        WHERE move.picking_id = picking.id);

DROP INDEX uq_stock_pickings_legacy_allocation_demand;

ALTER TABLE stock_pickings
    DROP COLUMN legacy_allocation_demand_id,
    DROP COLUMN order_id;

DROP TABLE allocation_demand_lines;
DROP TABLE allocation_demands;
