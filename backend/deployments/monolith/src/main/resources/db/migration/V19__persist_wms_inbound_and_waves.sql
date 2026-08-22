CREATE TABLE wms_inbound_operations (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    facility_id UUID NOT NULL,
    external_reference VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT uq_wms_inbound_operations_external_reference UNIQUE (external_reference),
    CONSTRAINT ck_wms_inbound_operations_status CHECK (status IN (
        'REGISTERED', 'ARRIVED', 'READY_FOR_PUTAWAY', 'QUARANTINED', 'COMPLETED'))
);

CREATE TABLE wms_inbound_expected_lines (
    id UUID PRIMARY KEY,
    inbound_operation_id UUID NOT NULL,
    sku_code VARCHAR(64) NOT NULL,
    expected_quantity INTEGER NOT NULL,

    CONSTRAINT fk_wms_inbound_expected_lines_operation
        FOREIGN KEY (inbound_operation_id) REFERENCES wms_inbound_operations(id) ON DELETE CASCADE,
    CONSTRAINT ck_wms_inbound_expected_lines_quantity CHECK (expected_quantity > 0)
);

CREATE INDEX idx_wms_inbound_expected_lines_operation
    ON wms_inbound_expected_lines (inbound_operation_id, sku_code, id);

CREATE TABLE wms_waves (
    id UUID PRIMARY KEY,
    facility_id UUID NOT NULL,
    template_code VARCHAR(128) NOT NULL,
    dispatch_by_cutoff TIMESTAMPTZ NOT NULL,
    max_shipments INTEGER NOT NULL,
    max_lines INTEGER NOT NULL,
    max_units INTEGER NOT NULL,
    planned_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(32) NOT NULL,
    warehouse_work_count INTEGER NOT NULL DEFAULT 0,
    pick_task_count INTEGER NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT ck_wms_waves_capacities CHECK (
        max_shipments > 0 AND max_lines > 0 AND max_units > 0),
    CONSTRAINT ck_wms_waves_counts CHECK (
        warehouse_work_count >= 0 AND pick_task_count >= 0),
    CONSTRAINT ck_wms_waves_status CHECK (status IN ('PLANNED', 'RELEASED', 'COMPLETED'))
);

CREATE TABLE wms_wave_assignments (
    shipment_id UUID PRIMARY KEY,
    wave_id UUID NOT NULL,
    line_count INTEGER NOT NULL,
    unit_count INTEGER NOT NULL,
    release_priority INTEGER NOT NULL,
    dispatch_by TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_wms_wave_assignments_wave
        FOREIGN KEY (wave_id) REFERENCES wms_waves(id) ON DELETE CASCADE,
    CONSTRAINT fk_wms_wave_assignments_shipment
        FOREIGN KEY (shipment_id) REFERENCES wms_shipments(id),
    CONSTRAINT ck_wms_wave_assignments_counts CHECK (line_count > 0 AND unit_count > 0),
    CONSTRAINT ck_wms_wave_assignments_priority CHECK (release_priority BETWEEN 0 AND 100)
);

CREATE INDEX idx_wms_wave_assignments_wave
    ON wms_wave_assignments (wave_id, shipment_id);
