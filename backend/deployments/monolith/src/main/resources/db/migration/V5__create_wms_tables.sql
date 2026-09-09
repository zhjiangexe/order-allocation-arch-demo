-- WMS 出貨、波次、揀貨、交運與收貨的獨立持久化模型。

CREATE TABLE wms_shipments (
    id UUID NOT NULL,
    order_id UUID NOT NULL,
    owner_id UUID NOT NULL,
    facility_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    dispatch_by TIMESTAMPTZ NOT NULL,
    release_priority INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    wave_id UUID,
    cancellation_state VARCHAR(32),
    cancellation_request_id UUID,
    version BIGINT DEFAULT 0 NOT NULL,
    cancellation_requested_at TIMESTAMPTZ,
    cancellation_reason VARCHAR(512),
    cancelled_at TIMESTAMPTZ,
    stock_operation_id UUID NOT NULL,
    CONSTRAINT ck_wms_shipments_cancellation_matches_status CHECK (
        cancellation_state IS NULL
        OR (cancellation_state = 'REQUESTED' AND status = 'CANCELLING')
        OR (cancellation_state = 'COMPLETED' AND status = 'CANCELLED')
        OR (cancellation_state = 'REJECTED' AND status = 'HANDED_OVER_TO_CARRIER')),
    CONSTRAINT ck_wms_shipments_cancellation_metadata CHECK (
        (cancellation_state IS NULL
            AND cancellation_request_id IS NULL
            AND cancellation_requested_at IS NULL
            AND cancellation_reason IS NULL
            AND cancelled_at IS NULL)
        OR
        (cancellation_state IN ('REQUESTED', 'REJECTED')
            AND cancellation_request_id IS NOT NULL
            AND cancellation_requested_at IS NOT NULL
            AND cancellation_reason IS NOT NULL
            AND cancelled_at IS NULL)
        OR
        (cancellation_state = 'COMPLETED'
            AND cancellation_request_id IS NOT NULL
            AND cancellation_requested_at IS NOT NULL
            AND cancellation_reason IS NOT NULL
            AND cancelled_at IS NOT NULL)),
    CONSTRAINT ck_wms_shipments_cancellation_state CHECK (
        cancellation_state IS NULL OR cancellation_state IN ('REQUESTED', 'COMPLETED', 'REJECTED')),
    CONSTRAINT ck_wms_shipments_release_priority CHECK (release_priority BETWEEN 0 AND 100),
    CONSTRAINT ck_wms_shipments_status CHECK (status IN (
        'CREATED', 'WAVE_PLANNED', 'RELEASED', 'PICKING', 'PICKED', 'PACKED',
        'READY_FOR_DISPATCH', 'HANDED_OVER_TO_CARRIER', 'CANCELLING', 'CANCELLED')),
    CONSTRAINT uq_wms_shipments_stock_operation UNIQUE (stock_operation_id),
    CONSTRAINT wms_shipments_pkey PRIMARY KEY (id)
);

CREATE INDEX idx_wms_shipments_due_simulation
    ON wms_shipments (created_at, id)
    WHERE ((status)::TEXT = 'CREATED'::TEXT);

CREATE INDEX idx_wms_shipments_order
    ON wms_shipments (order_id, created_at, id);

CREATE INDEX idx_wms_shipments_wave_candidates
    ON wms_shipments (facility_id, release_priority DESC, dispatch_by, created_at, id)
    WHERE ((status)::TEXT = 'CREATED'::TEXT);

CREATE TABLE wms_shipment_lines (
    move_id UUID NOT NULL,
    shipment_id UUID NOT NULL,
    order_line_id UUID NOT NULL,
    sku_code VARCHAR(64) NOT NULL,
    source_location_id UUID NOT NULL,
    quantity INTEGER NOT NULL,
    CONSTRAINT ck_wms_shipment_lines_quantity CHECK (quantity > 0),
    CONSTRAINT wms_shipment_lines_pkey PRIMARY KEY (move_id),
    CONSTRAINT fk_wms_shipment_lines_shipment FOREIGN KEY (shipment_id) REFERENCES wms_shipments(id) ON DELETE CASCADE
);

CREATE INDEX idx_wms_shipment_lines_shipment
    ON wms_shipment_lines (shipment_id, order_line_id);

CREATE TABLE wms_waves (
    id UUID NOT NULL,
    facility_id UUID NOT NULL,
    template_code VARCHAR(128) NOT NULL,
    dispatch_by_cutoff TIMESTAMPTZ NOT NULL,
    max_shipments INTEGER NOT NULL,
    max_lines INTEGER NOT NULL,
    max_units INTEGER NOT NULL,
    planned_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(32) NOT NULL,
    picking_work_count INTEGER DEFAULT 0 NOT NULL,
    pick_task_count INTEGER DEFAULT 0 NOT NULL,
    version BIGINT DEFAULT 0 NOT NULL,
    CONSTRAINT ck_wms_waves_capacities CHECK (
        max_shipments > 0 AND max_lines > 0 AND max_units > 0),
    CONSTRAINT ck_wms_waves_counts CHECK (
        picking_work_count >= 0 AND pick_task_count >= 0),
    CONSTRAINT ck_wms_waves_status CHECK (status IN ('PLANNED', 'RELEASED', 'COMPLETED')),
    CONSTRAINT wms_waves_pkey PRIMARY KEY (id)
);

CREATE TABLE wms_wave_assignments (
    shipment_id UUID NOT NULL,
    wave_id UUID NOT NULL,
    line_count INTEGER NOT NULL,
    unit_count INTEGER NOT NULL,
    release_priority INTEGER NOT NULL,
    dispatch_by TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_wms_wave_assignments_counts CHECK (line_count > 0 AND unit_count > 0),
    CONSTRAINT ck_wms_wave_assignments_priority CHECK (release_priority BETWEEN 0 AND 100),
    CONSTRAINT wms_wave_assignments_pkey PRIMARY KEY (shipment_id),
    CONSTRAINT fk_wms_wave_assignments_shipment FOREIGN KEY (shipment_id) REFERENCES wms_shipments(id),
    CONSTRAINT fk_wms_wave_assignments_wave FOREIGN KEY (wave_id) REFERENCES wms_waves(id) ON DELETE CASCADE
);

CREATE INDEX idx_wms_wave_assignments_wave
    ON wms_wave_assignments (wave_id, shipment_id);

CREATE TABLE wms_picking_works (
    id UUID NOT NULL,
    wave_id UUID NOT NULL,
    shipment_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT DEFAULT 0 NOT NULL,
    CONSTRAINT ck_wms_picking_works_status CHECK (
        status IN ('OPEN', 'IN_PROGRESS', 'EXCEPTION', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT uq_wms_picking_works_shipment UNIQUE (shipment_id),
    CONSTRAINT wms_picking_works_pkey PRIMARY KEY (id),
    CONSTRAINT fk_wms_picking_works_shipment FOREIGN KEY (shipment_id) REFERENCES wms_shipments(id),
    CONSTRAINT fk_wms_picking_works_wave FOREIGN KEY (wave_id) REFERENCES wms_waves(id)
);

CREATE TABLE wms_pick_tasks (
    id UUID NOT NULL,
    order_line_id UUID NOT NULL,
    move_id UUID NOT NULL,
    sku_code VARCHAR(64) NOT NULL,
    source_location_id UUID NOT NULL,
    requested_quantity INTEGER NOT NULL,
    picked_quantity INTEGER DEFAULT 0 NOT NULL,
    status VARCHAR(32) NOT NULL,
    confirmed_at TIMESTAMPTZ,
    work_id UUID NOT NULL,
    CONSTRAINT ck_wms_pick_tasks_confirmation CHECK (
        (status IN ('PICKED', 'SHORT_PICKED') AND confirmed_at IS NOT NULL)
     OR (status IN ('PENDING', 'CANCELLED') AND confirmed_at IS NULL)),
    CONSTRAINT ck_wms_pick_tasks_quantities CHECK (
        requested_quantity > 0
        AND picked_quantity BETWEEN 0 AND requested_quantity),
    CONSTRAINT ck_wms_pick_tasks_status CHECK (status IN ('PENDING', 'PICKED', 'SHORT_PICKED', 'CANCELLED')),
    CONSTRAINT uq_wms_pick_tasks_move UNIQUE (move_id),
    CONSTRAINT wms_pick_tasks_pkey PRIMARY KEY (id),
    CONSTRAINT fk_wms_pick_tasks_work FOREIGN KEY (work_id) REFERENCES wms_picking_works(id) ON DELETE CASCADE
);

CREATE INDEX idx_wms_pick_tasks_work
    ON wms_pick_tasks (work_id, id);

CREATE TABLE wms_dispatches (
    id UUID NOT NULL,
    shipment_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL,
    packed_at TIMESTAMPTZ NOT NULL,
    staged_at TIMESTAMPTZ,
    handed_over_at TIMESTAMPTZ,
    version BIGINT DEFAULT 0 NOT NULL,
    CONSTRAINT ck_wms_dispatches_status CHECK (status IN ('PACKED', 'STAGED', 'HANDED_OVER', 'CANCELLED')),
    CONSTRAINT ck_wms_dispatches_timestamps CHECK (
        (status = 'PACKED' AND staged_at IS NULL AND handed_over_at IS NULL)
     OR (status = 'STAGED' AND staged_at IS NOT NULL AND handed_over_at IS NULL)
     OR (status = 'HANDED_OVER' AND staged_at IS NOT NULL AND handed_over_at IS NOT NULL)
     OR (status = 'CANCELLED' AND handed_over_at IS NULL)),
    CONSTRAINT uq_wms_dispatches_shipment UNIQUE (shipment_id),
    CONSTRAINT wms_dispatches_pkey PRIMARY KEY (id),
    CONSTRAINT fk_wms_dispatches_shipment FOREIGN KEY (shipment_id) REFERENCES wms_shipments(id)
);

CREATE TABLE wms_inbound_operations (
    id UUID NOT NULL,
    owner_id UUID NOT NULL,
    facility_id UUID NOT NULL,
    external_reference VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT DEFAULT 0 NOT NULL,
    CONSTRAINT ck_wms_inbound_operations_status CHECK (status IN (
        'REGISTERED', 'ARRIVED', 'READY_FOR_PUTAWAY', 'QUARANTINED', 'COMPLETED')),
    CONSTRAINT uq_wms_inbound_operations_external_reference UNIQUE (external_reference),
    CONSTRAINT wms_inbound_operations_pkey PRIMARY KEY (id)
);

CREATE TABLE wms_inbound_expected_lines (
    id UUID NOT NULL,
    inbound_operation_id UUID NOT NULL,
    sku_code VARCHAR(64) NOT NULL,
    expected_quantity INTEGER NOT NULL,
    CONSTRAINT ck_wms_inbound_expected_lines_quantity CHECK (expected_quantity > 0),
    CONSTRAINT wms_inbound_expected_lines_pkey PRIMARY KEY (id),
    CONSTRAINT fk_wms_inbound_expected_lines_operation FOREIGN KEY (inbound_operation_id) REFERENCES wms_inbound_operations(id) ON DELETE CASCADE
);

CREATE INDEX idx_wms_inbound_expected_lines_operation
    ON wms_inbound_expected_lines (inbound_operation_id, sku_code, id);
