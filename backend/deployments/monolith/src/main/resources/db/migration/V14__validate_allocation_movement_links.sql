-- Final movement-link shape. NOT VALID allows the constraints to be installed without a long table
-- scan lock; explicit validation is the cutover gate after the idempotent backfill.

ALTER TABLE stock_moves
    ADD CONSTRAINT ck_stock_moves_allocation_reference_pair CHECK (
        (allocation_demand_id IS NULL AND allocation_demand_line_id IS NULL)
     OR (allocation_demand_id IS NOT NULL AND allocation_demand_line_id IS NOT NULL
         AND source_line_id IS NOT NULL)
    ) NOT VALID,
    ADD CONSTRAINT ck_stock_moves_order_demand_reference CHECK (
        order_line_id IS NULL
        OR (allocation_demand_id IS NOT NULL
            AND source_line_id = order_line_id::text)
    ) NOT VALID,
    ADD CONSTRAINT fk_stock_moves_allocation_demand
        FOREIGN KEY (allocation_demand_id)
        REFERENCES allocation_demands(id) NOT VALID,
    ADD CONSTRAINT fk_stock_moves_allocation_demand_line
        FOREIGN KEY (allocation_demand_id, allocation_demand_line_id)
        REFERENCES allocation_demand_lines(allocation_demand_id, id) NOT VALID;

ALTER TABLE stock_moves VALIDATE CONSTRAINT ck_stock_moves_allocation_reference_pair;
ALTER TABLE stock_moves VALIDATE CONSTRAINT ck_stock_moves_order_demand_reference;
ALTER TABLE stock_moves VALIDATE CONSTRAINT fk_stock_moves_allocation_demand;
ALTER TABLE stock_moves VALIDATE CONSTRAINT fk_stock_moves_allocation_demand_line;

CREATE INDEX idx_stock_moves_allocation_demand
    ON stock_moves (allocation_demand_id, allocation_demand_line_id);
