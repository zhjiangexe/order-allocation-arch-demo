-- 庫存、配貨政策與現行 StockOperation／Move 模型。
-- owner_allocation_policies 為可選覆寫；無資料時沿用 application 的 DISPATCH_DATE_FIRST。

CREATE TABLE stock_pools (
    id UUID NOT NULL,
    owner_id UUID NOT NULL,
    location_id UUID NOT NULL,
    sku_code VARCHAR(64) NOT NULL,
    in_date DATE NOT NULL,
    expiry_date DATE NOT NULL,
    location_usage VARCHAR(32) DEFAULT 'INTERNAL'::VARCHAR NOT NULL,
    on_hand_quantity INTEGER NOT NULL,
    reserved_quantity INTEGER DEFAULT 0 NOT NULL,
    version BIGINT DEFAULT 0 NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_stock_pools_location_usage_internal CHECK (location_usage = 'INTERNAL'),
    CONSTRAINT ck_stock_pools_on_hand_non_negative CHECK (on_hand_quantity >= 0),
    CONSTRAINT ck_stock_pools_reserved_non_negative CHECK (reserved_quantity >= 0),
    CONSTRAINT ck_stock_pools_reserved_not_above_on_hand CHECK (reserved_quantity <= on_hand_quantity),
    CONSTRAINT stock_pools_pkey PRIMARY KEY (id),
    CONSTRAINT uq_stock_pools_batch UNIQUE (owner_id, location_id, sku_code, in_date, expiry_date),
    CONSTRAINT fk_stock_pools_internal_location FOREIGN KEY (location_id, location_usage) REFERENCES stock_locations(id, usage),
    CONSTRAINT fk_stock_pools_sku FOREIGN KEY (owner_id, sku_code) REFERENCES skus(owner_id, sku_code)
);

CREATE INDEX idx_stock_pools_fefo
    ON stock_pools (owner_id, location_id, sku_code, expiry_date, in_date, id);

CREATE TABLE owner_allocation_policies (
    owner_id UUID NOT NULL,
    sequence_policy VARCHAR(32) DEFAULT 'DISPATCH_DATE_FIRST'::VARCHAR NOT NULL,
    CONSTRAINT owner_allocation_policies_sequence_policy_check CHECK (sequence_policy IN ('FIFO', 'DISPATCH_DATE_FIRST')),
    CONSTRAINT owner_allocation_policies_pkey PRIMARY KEY (owner_id),
    CONSTRAINT owner_allocation_policies_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES owners(id)
);

CREATE TABLE stock_operation_types (
    id UUID NOT NULL,
    facility_id UUID NOT NULL,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(255) NOT NULL,
    default_from_location_id UUID NOT NULL,
    default_to_location_id UUID NOT NULL,
    CONSTRAINT ck_stock_operation_types_code CHECK (code IN ('INBOUND', 'OUTBOUND', 'INTERNAL')),
    CONSTRAINT stock_operation_types_pkey PRIMARY KEY (id),
    CONSTRAINT uq_stock_operation_types_facility_code UNIQUE (facility_id, code),
    CONSTRAINT fk_stock_operation_types_facility FOREIGN KEY (facility_id) REFERENCES facilities(id),
    CONSTRAINT fk_stock_operation_types_from_location FOREIGN KEY (default_from_location_id) REFERENCES stock_locations(id),
    CONSTRAINT fk_stock_operation_types_to_location FOREIGN KEY (default_to_location_id) REFERENCES stock_locations(id)
);

CREATE TABLE stock_operations (
    id UUID NOT NULL,
    stock_operation_type_id UUID NOT NULL,
    owner_id UUID NOT NULL,
    from_location_id UUID NOT NULL,
    to_location_id UUID NOT NULL,
    dispatch_by TIMESTAMPTZ,
    release_priority INTEGER,
    state VARCHAR(32) DEFAULT 'CONFIRMED'::VARCHAR NOT NULL,
    version BIGINT DEFAULT 0 NOT NULL,
    direction VARCHAR(32),
    source_type VARCHAR(32),
    source_id VARCHAR(255),
    allocation_unit_key VARCHAR(128),
    policy_code VARCHAR(64),
    enqueued_at TIMESTAMPTZ,
    CONSTRAINT ck_stock_operations_direction CHECK (direction IS NULL OR direction IN ('INBOUND', 'OUTBOUND', 'INTERNAL')),
    CONSTRAINT ck_stock_operations_scheduling_by_direction CHECK (
            direction IS NULL
         OR (direction = 'INBOUND' AND dispatch_by IS NULL AND release_priority IS NULL)
         OR (direction IN ('OUTBOUND', 'INTERNAL') AND dispatch_by IS NOT NULL
             AND release_priority BETWEEN 0 AND 100)
        ),
    CONSTRAINT ck_stock_operations_source_identity CHECK (
        (direction = 'OUTBOUND'
         AND source_type IS NOT NULL
         AND btrim(source_id) <> ''
         AND btrim(allocation_unit_key) <> ''
         AND policy_code = 'SHIP_COMPLETE'
         AND enqueued_at IS NOT NULL)
        OR
        (direction IS DISTINCT FROM 'OUTBOUND'
         AND source_type IS NULL
         AND source_id IS NULL
         AND allocation_unit_key IS NULL
         AND policy_code IS NULL
         AND enqueued_at IS NULL)
    ),
    CONSTRAINT ck_stock_operations_source_type CHECK (
        source_type IS NULL
        OR source_type IN ('ORDER', 'TRANSFER', 'REPLENISHMENT', 'PRODUCTION', 'MANUAL')
    ),
    CONSTRAINT ck_stock_operations_state CHECK (state IN ('CONFIRMED', 'ASSIGNED', 'DONE', 'CANCELLED')),
    CONSTRAINT stock_operations_pkey PRIMARY KEY (id),
    CONSTRAINT fk_stock_operations_from_location FOREIGN KEY (from_location_id) REFERENCES stock_locations(id),
    CONSTRAINT fk_stock_operations_owner FOREIGN KEY (owner_id) REFERENCES owners(id),
    CONSTRAINT fk_stock_operations_to_location FOREIGN KEY (to_location_id) REFERENCES stock_locations(id),
    CONSTRAINT fk_stock_operations_type FOREIGN KEY (stock_operation_type_id) REFERENCES stock_operation_types(id)
);

CREATE INDEX idx_stock_operations_confirmed_scope_queue
    ON stock_operations (owner_id, from_location_id, enqueued_at, id)
    WHERE (((state)::TEXT = 'CONFIRMED'::TEXT) AND ((direction)::TEXT = 'OUTBOUND'::TEXT));

CREATE INDEX idx_stock_operations_dispatch_sequence
    ON stock_operations (owner_id, from_location_id, dispatch_by, enqueued_at, id)
    WHERE (((state)::TEXT = 'CONFIRMED'::TEXT) AND ((direction)::TEXT = 'OUTBOUND'::TEXT));

CREATE UNIQUE INDEX uq_stock_operations_source_unit
    ON stock_operations (source_type, source_id, allocation_unit_key)
    WHERE (source_type IS NOT NULL);

CREATE TABLE stock_moves (
    id UUID NOT NULL,
    stock_operation_id UUID,
    owner_id UUID NOT NULL,
    sku_code VARCHAR(64) NOT NULL,
    from_location_id UUID NOT NULL,
    to_location_id UUID NOT NULL,
    demand_quantity INTEGER NOT NULL,
    state VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP NOT NULL,
    assigned_at TIMESTAMPTZ,
    version BIGINT DEFAULT 0 NOT NULL,
    source_line_id VARCHAR(255),
    line_sequence INTEGER,
    CONSTRAINT ck_stock_moves_assigned_at CHECK (
        (state = 'CONFIRMED' AND assigned_at IS NULL)
     OR (state <> 'CONFIRMED' AND (state = 'CANCELLED' OR assigned_at IS NOT NULL))
    ),
    CONSTRAINT ck_stock_moves_demand_quantity_positive CHECK (demand_quantity > 0),
    CONSTRAINT ck_stock_moves_source_line_identity CHECK (
        (source_line_id IS NULL AND line_sequence IS NULL)
        OR (btrim(source_line_id) <> '' AND line_sequence > 0)
    ),
    CONSTRAINT ck_stock_moves_state CHECK (state IN ('CONFIRMED', 'ASSIGNED', 'DONE', 'CANCELLED')),
    CONSTRAINT stock_moves_pkey PRIMARY KEY (id),
    CONSTRAINT fk_stock_moves_from_location FOREIGN KEY (from_location_id) REFERENCES stock_locations(id),
    CONSTRAINT fk_stock_moves_sku FOREIGN KEY (owner_id, sku_code) REFERENCES skus(owner_id, sku_code),
    CONSTRAINT fk_stock_moves_stock_operation FOREIGN KEY (stock_operation_id) REFERENCES stock_operations(id),
    CONSTRAINT fk_stock_moves_to_location FOREIGN KEY (to_location_id) REFERENCES stock_locations(id)
);

CREATE INDEX idx_stock_moves_confirmed_operation_sku
    ON stock_moves (stock_operation_id, sku_code)
    WHERE ((state)::TEXT = 'CONFIRMED'::TEXT);

CREATE INDEX idx_stock_moves_confirmed_scope_sku
    ON stock_moves (owner_id, from_location_id, sku_code, stock_operation_id)
    WHERE ((state)::TEXT = 'CONFIRMED'::TEXT);

CREATE INDEX idx_stock_moves_operation_ordered
    ON stock_moves (stock_operation_id, line_sequence, id);

CREATE UNIQUE INDEX uq_stock_moves_operation_line_sequence
    ON stock_moves (stock_operation_id, line_sequence)
    WHERE (line_sequence IS NOT NULL);

CREATE UNIQUE INDEX uq_stock_moves_operation_source_line
    ON stock_moves (stock_operation_id, source_line_id)
    WHERE (source_line_id IS NOT NULL);

CREATE TABLE stock_move_lines (
    id UUID NOT NULL,
    move_id UUID NOT NULL,
    stock_pool_id UUID NOT NULL,
    quantity INTEGER NOT NULL,
    CONSTRAINT ck_stock_move_lines_quantity_positive CHECK (quantity > 0),
    CONSTRAINT stock_move_lines_pkey PRIMARY KEY (id),
    CONSTRAINT uq_stock_move_lines_move_pool UNIQUE (move_id, stock_pool_id),
    CONSTRAINT fk_stock_move_lines_move FOREIGN KEY (move_id) REFERENCES stock_moves(id),
    CONSTRAINT fk_stock_move_lines_stock_pool FOREIGN KEY (stock_pool_id) REFERENCES stock_pools(id)
);

CREATE INDEX idx_stock_move_lines_stock_pool
    ON stock_move_lines (stock_pool_id);

CREATE TABLE stock_operation_cancellations (
    cancellation_operation_id UUID NOT NULL,
    state VARCHAR(32) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT DEFAULT 0 NOT NULL,
    stock_operation_id UUID NOT NULL,
    CONSTRAINT ck_stock_operation_cancellations_state CHECK (
        state IN ('STARTED', 'EXTERNAL_REJECTED', 'EXTERNAL_CONFIRMED', 'COMPLETED')
    ),
    CONSTRAINT stock_operation_cancellations_pkey PRIMARY KEY (stock_operation_id, cancellation_operation_id),
    CONSTRAINT fk_stock_operation_cancellations_stock_operation FOREIGN KEY (stock_operation_id) REFERENCES stock_operations(id)
);

CREATE TABLE stock_receipt_requests (
    receipt_id UUID NOT NULL,
    owner_id UUID NOT NULL,
    facility_id UUID NOT NULL,
    location_id UUID NOT NULL,
    sku_code VARCHAR(64) NOT NULL,
    in_date DATE NOT NULL,
    expiry_date DATE NOT NULL,
    quantity INTEGER NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_stock_receipt_requests_quantity_positive CHECK (quantity > 0),
    CONSTRAINT stock_receipt_requests_pkey PRIMARY KEY (receipt_id)
);
