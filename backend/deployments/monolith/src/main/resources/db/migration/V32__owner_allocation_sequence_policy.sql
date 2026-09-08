-- Seed existing owners; newly-created owners without an override use the same application default.
CREATE TABLE owner_allocation_policies (
    owner_id UUID PRIMARY KEY REFERENCES owners(id),
    sequence_policy VARCHAR(32) NOT NULL DEFAULT 'DISPATCH_DATE_FIRST' CHECK (sequence_policy IN ('FIFO', 'DISPATCH_DATE_FIRST'))
);
INSERT INTO owner_allocation_policies (owner_id, sequence_policy)
SELECT id, 'DISPATCH_DATE_FIRST' FROM owners;

CREATE INDEX idx_stock_operations_dispatch_sequence
    ON stock_operations(owner_id, from_location_id, dispatch_by, enqueued_at, id)
    WHERE state = 'CONFIRMED' AND direction = 'OUTBOUND';
