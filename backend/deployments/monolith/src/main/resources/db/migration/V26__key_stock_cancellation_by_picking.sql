-- Cancellation coordinates one canonical stock operation group. The durable operation state is
-- preserved; only its aggregate identity changes.

ALTER TABLE allocation_cancellation_operations
    DROP CONSTRAINT allocation_cancellation_operations_pkey,
    DROP CONSTRAINT fk_allocation_cancellation_operations_demand,
    ALTER COLUMN picking_id SET NOT NULL;

DROP INDEX idx_allocation_cancellation_operations_picking;

ALTER TABLE allocation_cancellation_operations
    RENAME TO stock_picking_cancellation_operations;

ALTER TABLE stock_picking_cancellation_operations
    DROP COLUMN allocation_demand_id,
    ADD CONSTRAINT stock_picking_cancellation_operations_pkey
        PRIMARY KEY (picking_id, operation_id),
    ADD CONSTRAINT fk_stock_picking_cancellation_operations_picking
        FOREIGN KEY (picking_id) REFERENCES stock_pickings(id);

ALTER TABLE stock_picking_cancellation_operations
    RENAME CONSTRAINT ck_allocation_cancellation_operations_state
    TO ck_stock_picking_cancellation_operations_state;
