ALTER TABLE wms_waves RENAME COLUMN warehouse_work_count TO picking_work_count;

CREATE TABLE wms_picking_works (
    id UUID PRIMARY KEY,
    wave_id UUID NOT NULL,
    shipment_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT uq_wms_picking_works_shipment UNIQUE (shipment_id),
    CONSTRAINT fk_wms_picking_works_wave FOREIGN KEY (wave_id) REFERENCES wms_waves(id),
    CONSTRAINT fk_wms_picking_works_shipment FOREIGN KEY (shipment_id) REFERENCES wms_shipments(id),
    CONSTRAINT ck_wms_picking_works_status CHECK (
        status IN ('OPEN', 'IN_PROGRESS', 'EXCEPTION', 'COMPLETED', 'CANCELLED'))
);

INSERT INTO wms_picking_works (id, wave_id, shipment_id, status, version)
SELECT work_id, work_wave_id, id, work_status, 0
FROM wms_shipments
WHERE work_id IS NOT NULL;

ALTER TABLE wms_pick_tasks ADD COLUMN work_id UUID;

UPDATE wms_pick_tasks task
SET work_id = shipment.work_id
FROM wms_shipments shipment
WHERE shipment.id = task.shipment_id;

ALTER TABLE wms_pick_tasks
    ALTER COLUMN work_id SET NOT NULL,
    ADD CONSTRAINT fk_wms_pick_tasks_work
        FOREIGN KEY (work_id) REFERENCES wms_picking_works(id) ON DELETE CASCADE,
    DROP CONSTRAINT fk_wms_pick_tasks_shipment,
    DROP COLUMN shipment_id;

CREATE INDEX idx_wms_pick_tasks_work ON wms_pick_tasks (work_id, id);

ALTER TABLE wms_shipments
    DROP CONSTRAINT ck_wms_shipments_work,
    DROP CONSTRAINT ck_wms_shipments_work_status,
    DROP COLUMN work_id,
    DROP COLUMN work_wave_id,
    DROP COLUMN work_status;

CREATE TABLE wms_dispatches (
    id UUID PRIMARY KEY,
    shipment_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL,
    packed_at TIMESTAMPTZ NOT NULL,
    staged_at TIMESTAMPTZ,
    handed_over_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT uq_wms_dispatches_shipment UNIQUE (shipment_id),
    CONSTRAINT fk_wms_dispatches_shipment FOREIGN KEY (shipment_id) REFERENCES wms_shipments(id),
    CONSTRAINT ck_wms_dispatches_status CHECK (status IN ('PACKED', 'STAGED', 'HANDED_OVER', 'CANCELLED')),
    CONSTRAINT ck_wms_dispatches_timestamps CHECK (
        (status = 'PACKED' AND staged_at IS NULL AND handed_over_at IS NULL)
     OR (status = 'STAGED' AND staged_at IS NOT NULL AND handed_over_at IS NULL)
     OR (status = 'HANDED_OVER' AND staged_at IS NOT NULL AND handed_over_at IS NOT NULL)
     OR (status = 'CANCELLED' AND handed_over_at IS NULL))
);

INSERT INTO wms_dispatches (id, shipment_id, status, packed_at, staged_at, handed_over_at, version)
SELECT id,
       id,
       CASE status
           WHEN 'PACKED' THEN 'PACKED'
           WHEN 'READY_FOR_DISPATCH' THEN 'STAGED'
           WHEN 'HANDED_OVER_TO_CARRIER' THEN 'HANDED_OVER'
       END,
       created_at,
       CASE WHEN status IN ('READY_FOR_DISPATCH', 'HANDED_OVER_TO_CARRIER') THEN created_at END,
       CASE WHEN status = 'HANDED_OVER_TO_CARRIER' THEN created_at END,
       0
FROM wms_shipments
WHERE status IN ('PACKED', 'READY_FOR_DISPATCH', 'HANDED_OVER_TO_CARRIER');
