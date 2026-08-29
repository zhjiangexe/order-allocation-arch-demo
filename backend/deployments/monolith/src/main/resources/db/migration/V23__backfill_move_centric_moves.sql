-- Reuse every existing StockMove identity. Only a legacy pending or cancelled demand may be
-- missing its placeholder move; those non-physical rows are created with deterministic line IDs.

UPDATE stock_moves move
   SET picking_id = picking.id,
       source_line_id = line.source_line_id,
       line_sequence = line.line_sequence
  FROM allocation_demand_lines line
  JOIN stock_pickings picking
    ON picking.legacy_allocation_demand_id = line.allocation_demand_id
 WHERE move.allocation_demand_id = line.allocation_demand_id
   AND move.allocation_demand_line_id = line.id;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM allocation_demands demand
          JOIN allocation_demand_lines line ON line.allocation_demand_id = demand.id
         WHERE demand.status = 'ALLOCATED'
           AND NOT EXISTS (
               SELECT 1
                 FROM stock_moves move
                WHERE move.allocation_demand_id = demand.id
                  AND move.allocation_demand_line_id = line.id)
    ) THEN
        RAISE EXCEPTION 'move-centric backfill failed: allocated demand line has no movement evidence';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM allocation_demand_lines line
          JOIN stock_moves collision ON collision.id = line.id
         WHERE NOT EXISTS (
               SELECT 1
                 FROM stock_moves expected
                WHERE expected.allocation_demand_id = line.allocation_demand_id
                  AND expected.allocation_demand_line_id = line.id)
    ) THEN
        RAISE EXCEPTION 'move-centric backfill failed: deterministic move ID collides with an unrelated move';
    END IF;
END $$;

INSERT INTO stock_moves (
    id,
    picking_id,
    owner_id,
    sku_code,
    from_location_id,
    to_location_id,
    order_line_id,
    demand_quantity,
    state,
    created_at,
    assigned_at,
    version,
    allocation_demand_id,
    allocation_demand_line_id,
    source_line_id,
    line_sequence)
SELECT line.id,
       picking.id,
       demand.owner_id,
       line.sku_code,
       picking.from_location_id,
       picking.to_location_id,
       CASE
           WHEN demand.source_type = 'ORDER'
            AND line.source_line_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
           THEN line.source_line_id::UUID
           ELSE NULL
       END,
       line.quantity,
       CASE demand.status
           WHEN 'PENDING' THEN 'CONFIRMED'
           WHEN 'CANCELLED' THEN 'CANCELLED'
       END,
       demand.created_at,
       NULL,
       0,
       demand.id,
       line.id,
       line.source_line_id,
       line.line_sequence
  FROM allocation_demands demand
  JOIN allocation_demand_lines line ON line.allocation_demand_id = demand.id
  JOIN stock_pickings picking ON picking.legacy_allocation_demand_id = demand.id
 WHERE demand.status IN ('PENDING', 'CANCELLED')
   AND NOT EXISTS (
       SELECT 1
         FROM stock_moves move
        WHERE move.allocation_demand_id = demand.id
          AND move.allocation_demand_line_id = line.id);

UPDATE stock_pickings picking
   SET state = summary.move_state
  FROM (
      SELECT move.picking_id,
             CASE
                 WHEN BOOL_AND(move.state = 'CONFIRMED') THEN 'CONFIRMED'
                 WHEN BOOL_AND(move.state = 'ASSIGNED') THEN 'ASSIGNED'
                 WHEN BOOL_AND(move.state = 'DONE') THEN 'DONE'
                 WHEN BOOL_AND(move.state = 'CANCELLED') THEN 'CANCELLED'
                 ELSE NULL
             END AS move_state
        FROM stock_moves move
       WHERE move.picking_id IS NOT NULL
       GROUP BY move.picking_id
  ) summary
 WHERE picking.id = summary.picking_id
   AND summary.move_state IS NOT NULL;

UPDATE allocation_cancellation_operations operation
   SET picking_id = picking.id
  FROM stock_pickings picking
 WHERE picking.legacy_allocation_demand_id = operation.allocation_demand_id;

-- Before this cutover, the WMS allocation ID carried AllocationDemand.id.
UPDATE wms_shipments shipment
   SET picking_id = picking.id
  FROM stock_pickings picking
 WHERE picking.legacy_allocation_demand_id = shipment.allocation_id;
