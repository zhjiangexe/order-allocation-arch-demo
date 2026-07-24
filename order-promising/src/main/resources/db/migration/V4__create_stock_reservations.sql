CREATE TABLE stock_reservations (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    stock_pool_id UUID NOT NULL,
    quantity INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    reserved_at TIMESTAMPTZ NOT NULL,
    released_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_stock_reservations_order_id UNIQUE (order_id),
    CONSTRAINT fk_stock_reservations_order
        FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT fk_stock_reservations_stock_pool
        FOREIGN KEY (stock_pool_id) REFERENCES stock_pools(id),
    CONSTRAINT ck_stock_reservations_quantity_positive CHECK (quantity > 0),
    CONSTRAINT ck_stock_reservations_status
        CHECK (status IN ('ACTIVE', 'RELEASED')),
    CONSTRAINT ck_stock_reservations_released_state CHECK (
        (status = 'ACTIVE' AND released_at IS NULL)
        OR (status = 'RELEASED' AND released_at IS NOT NULL AND released_at >= reserved_at)
    )
);
