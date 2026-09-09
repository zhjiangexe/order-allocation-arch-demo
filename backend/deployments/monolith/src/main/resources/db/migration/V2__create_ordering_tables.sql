-- 訂單最終模型：配貨、取消與履約關聯直接定義於建表；排序採 received_at。

CREATE TABLE orders (
    id UUID NOT NULL,
    owner_id UUID NOT NULL,
    external_order_no VARCHAR(128) NOT NULL,
    ship_to_zone VARCHAR(32) NOT NULL,
    ship_to_address VARCHAR(512) NOT NULL,
    promised_delivery_date DATE NOT NULL,
    dispatch_by TIMESTAMPTZ NOT NULL,
    release_priority INTEGER NOT NULL,
    facility_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    placed_at TIMESTAMPTZ,
    allocated_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    version BIGINT DEFAULT 0 NOT NULL,
    fulfilled_at TIMESTAMPTZ,
    cancellation_request_id UUID,
    cancellation_reason VARCHAR(512),
    fulfilled_by_shipment_id UUID,
    CONSTRAINT ck_orders_cancellation_correlation CHECK ((cancellation_request_id IS NULL) = (cancellation_reason IS NULL)),
    CONSTRAINT ck_orders_release_priority CHECK (release_priority BETWEEN 0 AND 100),
    CONSTRAINT orders_pkey PRIMARY KEY (id),
    CONSTRAINT uq_orders_owner_external_no UNIQUE (owner_id, external_order_no),
    CONSTRAINT fk_orders_owner FOREIGN KEY (owner_id) REFERENCES owners(id),
    CONSTRAINT fk_orders_owner_facility FOREIGN KEY (owner_id, facility_id) REFERENCES owner_facilities(owner_id, facility_id)
);

CREATE INDEX idx_orders_recent
    ON orders (received_at DESC, id DESC);

CREATE UNIQUE INDEX uq_orders_cancellation_request
    ON orders (cancellation_request_id)
    WHERE (cancellation_request_id IS NOT NULL);

CREATE UNIQUE INDEX uq_orders_fulfilled_shipment
    ON orders (fulfilled_by_shipment_id)
    WHERE (fulfilled_by_shipment_id IS NOT NULL);

CREATE TABLE order_lines (
    id UUID NOT NULL,
    order_id UUID NOT NULL,
    line_no INTEGER NOT NULL,
    owner_id UUID NOT NULL,
    sku_code VARCHAR(64) NOT NULL,
    quantity INTEGER NOT NULL,
    CONSTRAINT ck_order_lines_quantity_positive CHECK (quantity > 0),
    CONSTRAINT order_lines_pkey PRIMARY KEY (id),
    CONSTRAINT uq_order_lines_order_line_no UNIQUE (order_id, line_no),
    CONSTRAINT fk_order_lines_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT fk_order_lines_sku FOREIGN KEY (owner_id, sku_code) REFERENCES skus(owner_id, sku_code)
);

CREATE INDEX idx_order_lines_demand_fifo
    ON order_lines (owner_id, sku_code, order_id);
