-- Establish exactly one canonical StockPicking for every legacy AllocationDemand. A temporary
-- legacy link makes the data conversion explicit and is removed only after validation.

DO $$
BEGIN
    IF EXISTS (
        SELECT move.picking_id
          FROM stock_moves move
         WHERE move.picking_id IS NOT NULL
           AND move.allocation_demand_id IS NOT NULL
         GROUP BY move.picking_id
        HAVING COUNT(DISTINCT move.allocation_demand_id) > 1
    ) THEN
        RAISE EXCEPTION 'move-centric backfill failed: one picking references multiple allocation demands';
    END IF;

    IF EXISTS (
        SELECT move.allocation_demand_id
          FROM stock_moves move
         WHERE move.picking_id IS NOT NULL
           AND move.allocation_demand_id IS NOT NULL
         GROUP BY move.allocation_demand_id
        HAVING COUNT(DISTINCT move.picking_id) > 1
    ) THEN
        RAISE EXCEPTION 'move-centric backfill failed: one allocation demand references multiple pickings';
    END IF;
END $$;

UPDATE stock_pickings picking
   SET source_type = demand.source_type,
       source_id = demand.source_id,
       allocation_unit_key = demand.allocation_unit_key,
       policy_code = 'SHIP_COMPLETE',
       enqueued_at = demand.enqueued_at,
       legacy_allocation_demand_id = demand.id,
       direction = 'OUTBOUND',
       dispatch_by = demand.required_by,
       release_priority = demand.release_priority
  FROM allocation_demands demand
  JOIN (
      SELECT DISTINCT allocation_demand_id, picking_id
        FROM stock_moves
       WHERE allocation_demand_id IS NOT NULL
         AND picking_id IS NOT NULL
  ) existing ON existing.allocation_demand_id = demand.id
 WHERE picking.id = existing.picking_id;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM allocation_demands demand
          JOIN stock_pickings collision ON collision.id = demand.id
         WHERE collision.legacy_allocation_demand_id IS DISTINCT FROM demand.id
           AND NOT EXISTS (
               SELECT 1
                 FROM stock_pickings mapped
                WHERE mapped.legacy_allocation_demand_id = demand.id)
    ) THEN
        RAISE EXCEPTION 'move-centric backfill failed: deterministic picking ID collides with an unrelated picking';
    END IF;
END $$;

INSERT INTO stock_pickings (
    id,
    picking_type_id,
    owner_id,
    order_id,
    from_location_id,
    to_location_id,
    dispatch_by,
    release_priority,
    state,
    version,
    direction,
    source_type,
    source_id,
    allocation_unit_key,
    policy_code,
    enqueued_at,
    legacy_allocation_demand_id)
SELECT demand.id,
       outbound.id,
       demand.owner_id,
       CASE
           WHEN demand.source_type = 'ORDER'
            AND demand.source_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
           THEN demand.source_id::UUID
           ELSE NULL
       END,
       demand.location_id,
       outbound.default_to_location_id,
       demand.required_by,
       demand.release_priority,
       CASE demand.status
           WHEN 'PENDING' THEN 'CONFIRMED'
           WHEN 'ALLOCATED' THEN 'ASSIGNED'
           WHEN 'CANCELLED' THEN 'CANCELLED'
       END,
       0,
       'OUTBOUND',
       demand.source_type,
       demand.source_id,
       demand.allocation_unit_key,
       'SHIP_COMPLETE',
       demand.enqueued_at,
       demand.id
  FROM allocation_demands demand
  JOIN stock_picking_types outbound
    ON outbound.facility_id = demand.facility_id
   AND outbound.code = 'OUTBOUND'
 WHERE NOT EXISTS (
       SELECT 1
         FROM stock_pickings mapped
        WHERE mapped.legacy_allocation_demand_id = demand.id);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM allocation_demands demand
         WHERE NOT EXISTS (
               SELECT 1
                 FROM stock_pickings picking
                WHERE picking.legacy_allocation_demand_id = demand.id)
    ) THEN
        RAISE EXCEPTION 'move-centric backfill failed: allocation demand has no canonical picking';
    END IF;
END $$;
