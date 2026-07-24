CREATE TABLE stock_pools (
    id UUID PRIMARY KEY,
    sku VARCHAR(255) NOT NULL,
    on_hand_quantity INTEGER NOT NULL,
    reserved_quantity INTEGER NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_stock_pools_sku UNIQUE (sku),
    CONSTRAINT ck_stock_pools_on_hand_non_negative CHECK (on_hand_quantity >= 0),
    CONSTRAINT ck_stock_pools_reserved_non_negative CHECK (reserved_quantity >= 0),
    CONSTRAINT ck_stock_pools_reserved_not_above_on_hand
        CHECK (reserved_quantity <= on_hand_quantity)
);
