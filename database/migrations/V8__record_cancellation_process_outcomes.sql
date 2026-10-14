ALTER TABLE cancellation_processes
    ADD COLUMN wms_outcome VARCHAR(32),
    ADD COLUMN ordering_outcome VARCHAR(32);

ALTER TABLE cancellation_processes
    ADD CONSTRAINT ck_cancellation_wms_outcome CHECK (
        wms_outcome IS NULL OR wms_outcome IN ('SHIPMENT_CANCELLED', 'NO_SHIPMENT', 'REJECTED', 'MULTIPLE_SHIPMENTS')),
    ADD CONSTRAINT ck_cancellation_ordering_outcome CHECK (
        ordering_outcome IS NULL OR ordering_outcome IN ('SUCCEEDED', 'REJECTED'));
