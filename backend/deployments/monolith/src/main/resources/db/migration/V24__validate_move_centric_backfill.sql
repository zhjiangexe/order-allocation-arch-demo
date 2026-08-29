-- Fail-fast cutover checks. A mismatch must stop deployment before demand and order-specific
-- references are removed.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM allocation_demands demand
          LEFT JOIN stock_pickings picking
            ON picking.legacy_allocation_demand_id = demand.id
         GROUP BY demand.id
        HAVING COUNT(picking.id) <> 1
    ) OR EXISTS (
        SELECT 1
          FROM allocation_demand_lines line
          LEFT JOIN stock_moves move
            ON move.allocation_demand_id = line.allocation_demand_id
           AND move.allocation_demand_line_id = line.id
         GROUP BY line.id
        HAVING COUNT(move.id) <> 1
    ) THEN
        RAISE EXCEPTION 'move-centric cutover failed: source-to-picking-or-move cardinality mismatch';
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM allocation_demands demand
          JOIN stock_pickings picking ON picking.legacy_allocation_demand_id = demand.id
         WHERE picking.source_type IS DISTINCT FROM demand.source_type
            OR picking.source_id IS DISTINCT FROM demand.source_id
            OR picking.allocation_unit_key IS DISTINCT FROM demand.allocation_unit_key
            OR picking.owner_id IS DISTINCT FROM demand.owner_id
            OR picking.from_location_id IS DISTINCT FROM demand.location_id
            OR picking.dispatch_by IS DISTINCT FROM demand.required_by
            OR picking.release_priority IS DISTINCT FROM demand.release_priority
            OR picking.enqueued_at IS DISTINCT FROM demand.enqueued_at
            OR picking.policy_code IS DISTINCT FROM 'SHIP_COMPLETE'
            OR picking.direction IS DISTINCT FROM 'OUTBOUND'
    ) OR EXISTS (
        SELECT 1
          FROM allocation_demand_lines line
          JOIN stock_moves move
            ON move.allocation_demand_id = line.allocation_demand_id
           AND move.allocation_demand_line_id = line.id
          JOIN stock_pickings picking ON picking.id = move.picking_id
         WHERE picking.legacy_allocation_demand_id IS DISTINCT FROM line.allocation_demand_id
            OR move.source_line_id IS DISTINCT FROM line.source_line_id
            OR move.line_sequence IS DISTINCT FROM line.line_sequence
            OR move.sku_code IS DISTINCT FROM line.sku_code
            OR move.demand_quantity IS DISTINCT FROM line.quantity
            OR move.owner_id IS DISTINCT FROM picking.owner_id
            OR move.from_location_id IS DISTINCT FROM picking.from_location_id
            OR move.to_location_id IS DISTINCT FROM picking.to_location_id
    ) THEN
        RAISE EXCEPTION 'move-centric cutover failed: immutable source content drift';
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM stock_pickings picking
         WHERE picking.legacy_allocation_demand_id IS NOT NULL
           AND NOT EXISTS (
               SELECT 1
                 FROM stock_moves move
                WHERE move.picking_id = picking.id)
    ) OR EXISTS (
        SELECT 1
          FROM stock_pickings picking
          JOIN stock_moves move ON move.picking_id = picking.id
         WHERE picking.legacy_allocation_demand_id IS NOT NULL
           AND move.state <> picking.state
    ) THEN
        RAISE EXCEPTION 'move-centric cutover failed: picking and move states are not homogeneous';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM allocation_demands demand
          JOIN stock_pickings picking ON picking.legacy_allocation_demand_id = demand.id
         WHERE (demand.status = 'PENDING' AND picking.state <> 'CONFIRMED')
            OR (demand.status = 'ALLOCATED' AND picking.state NOT IN ('ASSIGNED', 'DONE'))
            OR (demand.status = 'CANCELLED' AND picking.state <> 'CANCELLED')
    ) THEN
        RAISE EXCEPTION 'move-centric cutover failed: legacy and movement lifecycle mismatch';
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (
        SELECT move.id
          FROM stock_moves move
          LEFT JOIN stock_move_lines move_line ON move_line.move_id = move.id
         WHERE move.allocation_demand_id IS NOT NULL
         GROUP BY move.id, move.state, move.demand_quantity
        HAVING (move.state IN ('CONFIRMED', 'CANCELLED') AND COUNT(move_line.id) <> 0)
            OR (move.state IN ('ASSIGNED', 'DONE')
                AND COALESCE(SUM(move_line.quantity), 0) <> move.demand_quantity)
    ) THEN
        RAISE EXCEPTION 'move-centric cutover failed: movement-line coverage mismatch';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM stock_pools pool
         WHERE pool.reserved_quantity <> COALESCE((
               SELECT SUM(move_line.quantity)
                 FROM stock_move_lines move_line
                 JOIN stock_moves move ON move.id = move_line.move_id
                WHERE move_line.stock_pool_id = pool.id
                  AND move.state = 'ASSIGNED'
           ), 0)
    ) THEN
        RAISE EXCEPTION 'move-centric cutover failed: stock reserved counter mismatch';
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM allocation_cancellation_operations
         WHERE picking_id IS NULL
    ) OR EXISTS (
        SELECT 1
          FROM wms_shipments
         WHERE picking_id IS NULL
    ) THEN
        RAISE EXCEPTION 'move-centric cutover failed: downstream picking reference missing';
    END IF;
END $$;
