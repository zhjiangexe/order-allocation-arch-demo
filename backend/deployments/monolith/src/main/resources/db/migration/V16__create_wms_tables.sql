CREATE TABLE wms_shipments (
    id UUID PRIMARY KEY,
    allocation_id UUID NOT NULL,
    order_id UUID NOT NULL,
    owner_id UUID NOT NULL,
    facility_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    dispatch_by TIMESTAMPTZ NOT NULL,
    release_priority INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    wave_id UUID,
    work_id UUID,
    work_wave_id UUID,
    work_status VARCHAR(32),
    cancellation_outcome VARCHAR(32),
    cancellation_request_id VARCHAR(255),
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT uq_wms_shipments_allocation UNIQUE (allocation_id),
    CONSTRAINT ck_wms_shipments_release_priority
        CHECK (release_priority BETWEEN 0 AND 100),
    CONSTRAINT ck_wms_shipments_status CHECK (status IN (
        'CREATED', 'WAVE_PLANNED', 'RELEASED', 'PICKING', 'PICKED', 'PACKED',
        'READY_FOR_DISPATCH', 'HANDED_OVER_TO_CARRIER', 'CANCELLING', 'CANCELLED')),
    CONSTRAINT ck_wms_shipments_work CHECK (
        (work_id IS NULL AND work_wave_id IS NULL AND work_status IS NULL)
     OR (work_id IS NOT NULL AND work_wave_id IS NOT NULL AND work_status IS NOT NULL)),
    CONSTRAINT ck_wms_shipments_work_status CHECK (
        work_status IS NULL OR work_status IN (
            'OPEN', 'IN_PROGRESS', 'EXCEPTION', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_wms_shipments_cancellation_outcome CHECK (
        cancellation_outcome IS NULL OR cancellation_outcome IN (
            'CANCELLED', 'ALREADY_CANCELLED', 'PUTBACK_REQUIRED',
            'REJECTED_AFTER_HANDOVER')),
    CONSTRAINT ck_wms_shipments_cancellation_request CHECK (
        cancellation_request_id IS NULL OR cancellation_outcome IS NOT NULL)
);

CREATE INDEX idx_wms_shipments_order
    ON wms_shipments (order_id, created_at, id);

CREATE INDEX idx_wms_shipments_wave_candidates
    ON wms_shipments (
        facility_id, release_priority DESC, dispatch_by, created_at, id)
    WHERE status = 'CREATED';

CREATE TABLE wms_shipment_lines (
    move_id UUID PRIMARY KEY,
    shipment_id UUID NOT NULL,
    order_line_id UUID NOT NULL,
    sku_code VARCHAR(64) NOT NULL,
    source_location_id UUID NOT NULL,
    quantity INTEGER NOT NULL,

    CONSTRAINT fk_wms_shipment_lines_shipment
        FOREIGN KEY (shipment_id) REFERENCES wms_shipments(id) ON DELETE CASCADE,
    CONSTRAINT ck_wms_shipment_lines_quantity CHECK (quantity > 0)
);

CREATE INDEX idx_wms_shipment_lines_shipment
    ON wms_shipment_lines (shipment_id, order_line_id);

CREATE TABLE wms_pick_tasks (
    id UUID PRIMARY KEY,
    shipment_id UUID NOT NULL,
    order_line_id UUID NOT NULL,
    move_id UUID NOT NULL,
    sku_code VARCHAR(64) NOT NULL,
    source_location_id UUID NOT NULL,
    requested_quantity INTEGER NOT NULL,
    picked_quantity INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    confirmed_at TIMESTAMPTZ,

    CONSTRAINT fk_wms_pick_tasks_shipment
        FOREIGN KEY (shipment_id) REFERENCES wms_shipments(id) ON DELETE CASCADE,
    CONSTRAINT uq_wms_pick_tasks_move UNIQUE (move_id),
    CONSTRAINT ck_wms_pick_tasks_quantities CHECK (
        requested_quantity > 0
        AND picked_quantity BETWEEN 0 AND requested_quantity),
    CONSTRAINT ck_wms_pick_tasks_status
        CHECK (status IN ('PENDING', 'PICKED', 'SHORT_PICKED', 'CANCELLED')),
    CONSTRAINT ck_wms_pick_tasks_confirmation CHECK (
        (status IN ('PICKED', 'SHORT_PICKED') AND confirmed_at IS NOT NULL)
     OR (status IN ('PENDING', 'CANCELLED') AND confirmed_at IS NULL))
);

CREATE INDEX idx_wms_pick_tasks_shipment
    ON wms_pick_tasks (shipment_id, id);
