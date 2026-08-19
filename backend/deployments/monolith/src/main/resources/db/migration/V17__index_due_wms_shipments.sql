CREATE INDEX idx_wms_shipments_due_simulation
    ON wms_shipments (created_at, id)
    WHERE status = 'CREATED';
