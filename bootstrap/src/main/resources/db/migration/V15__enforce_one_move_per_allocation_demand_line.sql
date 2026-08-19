-- Acceptance creates exactly one outbound move for each allocation demand line.
-- The foreign key added in V14 proves that a referenced line belongs to the same demand; this
-- partial unique index adds the missing "at most one move" half of that invariant.

-- V14's non-unique index has the same leading columns and predicate coverage, so the unique index
-- replaces it instead of making every move write maintain two equivalent indexes.
DROP INDEX idx_stock_moves_allocation_demand;

CREATE UNIQUE INDEX uq_stock_moves_allocation_demand_line
    ON stock_moves (allocation_demand_id, allocation_demand_line_id)
    WHERE allocation_demand_id IS NOT NULL;
