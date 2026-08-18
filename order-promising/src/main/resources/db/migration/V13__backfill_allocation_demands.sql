-- Backfill every historical order allocation unit. Existing execution owns the source-location
-- snapshot; only an order with no move uses the current outbound default. No completion event is
-- emitted by a data migration.

INSERT INTO allocation_demands (
    id,
    source_type,
    source_id,
    allocation_unit_key,
    owner_id,
    facility_id,
    location_id,
    required_by,
    release_priority,
    enqueued_at,
    accepted_content_version,
    status,
    version,
    created_at,
    updated_at
)
SELECT md5('allocation-demand:ORDER:' || o.id::text || ':PRIMARY')::uuid,
       'ORDER',
       o.id::text,
       'PRIMARY',
       o.owner_id,
       o.facility_id,
       COALESCE(existing_execution.from_location_id, outbound.default_from_location_id),
       o.dispatch_by,
       o.release_priority,
       COALESCE(existing_execution.enqueued_at, o.received_at),
       1,
       CASE
           WHEN o.status = 'CANCELLED' THEN 'CANCELLED'
           WHEN o.status IN ('ALLOCATED', 'FULFILLED')
             OR existing_execution.has_assigned
             THEN 'ALLOCATED'
           ELSE 'PENDING'
       END,
       0,
       o.received_at,
       CURRENT_TIMESTAMP
  FROM orders o
  JOIN stock_picking_types outbound
    ON outbound.facility_id = o.facility_id
   AND outbound.code = 'OUTBOUND'
  LEFT JOIN LATERAL (
      SELECT first_move.from_location_id,
             summary.enqueued_at,
             summary.has_assigned
        FROM (
            SELECT MIN(m.created_at) AS enqueued_at,
                   BOOL_OR(m.state IN ('ASSIGNED', 'DONE')) AS has_assigned
              FROM stock_moves m
              JOIN order_lines ol ON ol.id = m.order_line_id
             WHERE ol.order_id = o.id
        ) summary
        JOIN LATERAL (
            SELECT m.from_location_id
              FROM stock_moves m
              JOIN order_lines ol ON ol.id = m.order_line_id
             WHERE ol.order_id = o.id
             ORDER BY m.created_at, m.id
             LIMIT 1
        ) first_move ON TRUE
  ) existing_execution ON TRUE
ON CONFLICT (source_type, source_id, allocation_unit_key) DO NOTHING;

INSERT INTO allocation_demand_lines (
    id,
    allocation_demand_id,
    source_line_id,
    sku_code,
    quantity,
    line_sequence
)
SELECT md5('allocation-demand-line:ORDER:' || ol.id::text)::uuid,
       demand.id,
       ol.id::text,
       ol.sku_code,
       ol.quantity,
       ROW_NUMBER() OVER (PARTITION BY ol.order_id ORDER BY ol.id)
  FROM order_lines ol
  JOIN allocation_demands demand
    ON demand.source_type = 'ORDER'
   AND demand.source_id = ol.order_id::text
   AND demand.allocation_unit_key = 'PRIMARY'
ON CONFLICT (allocation_demand_id, source_line_id) DO NOTHING;

UPDATE stock_moves m
   SET allocation_demand_id = dl.allocation_demand_id,
       allocation_demand_line_id = dl.id,
       source_line_id = dl.source_line_id
  FROM allocation_demand_lines dl
 WHERE m.order_line_id IS NOT NULL
   AND dl.source_line_id = m.order_line_id::text;
