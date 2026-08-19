-- Allocation-owned demand lifecycle. Source aggregates remain in their own contexts; this schema
-- stores only canonical source identity, one inventory scope, scheduling snapshots and demand lines.

ALTER TABLE stock_locations
    ADD CONSTRAINT uq_stock_locations_id_facility UNIQUE (id, facility_id);

-- Direction belongs to warehouse execution, not to the optional order adapter link.  Keep the
-- column nullable during rolling-version coexistence so an older writer can still insert a
-- picking; new writers always populate it and readers have a legacy scheduling-field fallback.
ALTER TABLE stock_pickings
    ADD COLUMN direction VARCHAR(32);

UPDATE stock_pickings p
   SET direction = t.code
  FROM stock_picking_types t
 WHERE t.id = p.picking_type_id;

ALTER TABLE stock_pickings
    DROP CONSTRAINT ck_stock_pickings_fulfillment_terms,
    ADD CONSTRAINT ck_stock_pickings_direction
        CHECK (direction IS NULL OR direction IN ('INBOUND', 'OUTBOUND', 'INTERNAL')),
    ADD CONSTRAINT ck_stock_pickings_scheduling_by_direction
        CHECK (
            direction IS NULL
         OR (direction = 'INBOUND' AND dispatch_by IS NULL AND release_priority IS NULL)
         OR (direction IN ('OUTBOUND', 'INTERNAL') AND dispatch_by IS NOT NULL
             AND release_priority BETWEEN 0 AND 100)
        );

-- Stable order-source adapter snapshot. Unlike demand_lines, this remains readable after execution
-- rows exist so retries can compare immutable accepted content instead of silently becoming no-op.
CREATE VIEW allocation_order_source_demands AS
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

CREATE TABLE allocation_demands (
    id UUID PRIMARY KEY,
    source_type VARCHAR(32) NOT NULL,
    source_id VARCHAR(255) NOT NULL,
    allocation_unit_key VARCHAR(128) NOT NULL,
    owner_id UUID NOT NULL,
    facility_id UUID NOT NULL,
    location_id UUID NOT NULL,
    required_by TIMESTAMPTZ NOT NULL,
    release_priority INTEGER NOT NULL,
    enqueued_at TIMESTAMPTZ NOT NULL,
    accepted_content_version INTEGER NOT NULL DEFAULT 1,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_allocation_demands_source_unit
        UNIQUE (source_type, source_id, allocation_unit_key),
    CONSTRAINT fk_allocation_demands_owner_facility
        FOREIGN KEY (owner_id, facility_id)
        REFERENCES owner_facilities(owner_id, facility_id),
    CONSTRAINT fk_allocation_demands_location_facility
        FOREIGN KEY (location_id, facility_id)
        REFERENCES stock_locations(id, facility_id),
    CONSTRAINT ck_allocation_demands_source_type CHECK (
        source_type IN ('ORDER', 'TRANSFER', 'REPLENISHMENT', 'PRODUCTION', 'MANUAL')
    ),
    CONSTRAINT ck_allocation_demands_source_identity CHECK (
        btrim(source_id) <> '' AND btrim(allocation_unit_key) <> ''
    ),
    CONSTRAINT ck_allocation_demands_release_priority
        CHECK (release_priority BETWEEN 0 AND 100),
    CONSTRAINT ck_allocation_demands_content_version
        CHECK (accepted_content_version > 0),
    CONSTRAINT ck_allocation_demands_status
        CHECK (status IN ('PENDING', 'ALLOCATED', 'CANCELLED'))
);

CREATE TABLE allocation_demand_lines (
    id UUID PRIMARY KEY,
    allocation_demand_id UUID NOT NULL,
    source_line_id VARCHAR(255) NOT NULL,
    sku_code VARCHAR(64) NOT NULL,
    quantity INTEGER NOT NULL,
    line_sequence INTEGER NOT NULL,

    CONSTRAINT uq_allocation_demand_lines_demand_id
        UNIQUE (allocation_demand_id, id),
    CONSTRAINT uq_allocation_demand_lines_source
        UNIQUE (allocation_demand_id, source_line_id),
    CONSTRAINT uq_allocation_demand_lines_sequence
        UNIQUE (allocation_demand_id, line_sequence),
    CONSTRAINT fk_allocation_demand_lines_demand
        FOREIGN KEY (allocation_demand_id) REFERENCES allocation_demands(id),
    CONSTRAINT ck_allocation_demand_lines_source CHECK (btrim(source_line_id) <> ''),
    CONSTRAINT ck_allocation_demand_lines_sku CHECK (btrim(sku_code) <> ''),
    CONSTRAINT ck_allocation_demand_lines_quantity CHECK (quantity > 0),
    CONSTRAINT ck_allocation_demand_lines_sequence CHECK (line_sequence > 0)
);

-- One row is one idempotent cancellation operation. EXTERNAL_CONFIRMED is deliberately distinct
-- from COMPLETED so a crash before local reservation release can resume instead of returning success.
CREATE TABLE allocation_cancellation_operations (
    allocation_demand_id UUID NOT NULL,
    operation_id UUID NOT NULL,
    state VARCHAR(32) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,

    PRIMARY KEY (allocation_demand_id, operation_id),
    CONSTRAINT fk_allocation_cancellation_operations_demand
        FOREIGN KEY (allocation_demand_id) REFERENCES allocation_demands(id),
    CONSTRAINT ck_allocation_cancellation_operations_state CHECK (
        state IN ('STARTED', 'EXTERNAL_REJECTED', 'EXTERNAL_CONFIRMED', 'COMPLETED')
    )
);

CREATE INDEX idx_allocation_demands_pending_scope
    ON allocation_demands
        (owner_id, facility_id, location_id, enqueued_at, id)
    WHERE status = 'PENDING';

CREATE INDEX idx_allocation_demand_lines_sku_queue
    ON allocation_demand_lines (sku_code, allocation_demand_id);

-- Rolling-compatible first stage: old binaries ignore these nullable columns. Backfill and validated
-- constraints are separate migrations so no partially linked row is accepted as the final shape.
ALTER TABLE stock_moves
    ADD COLUMN allocation_demand_id UUID,
    ADD COLUMN allocation_demand_line_id UUID,
    ADD COLUMN source_line_id VARCHAR(255);
