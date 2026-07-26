CREATE TABLE orders (
    id UUID PRIMARY KEY,
    sku VARCHAR(255) NOT NULL,
    quantity INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    placed_at TIMESTAMPTZ NOT NULL,
    allocated_at TIMESTAMPTZ,
    backordered_since TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_orders_quantity_positive CHECK (quantity > 0)
);

-- 此複合 index 配合以下查詢：
-- WHERE sku = ? AND status = 'BACKORDERED'
-- ORDER BY backordered_since ASC, id ASC
--
-- 複合 index 會依欄位順序建立索引，因此先放用來等值篩選的 sku、status，
-- 讓 PostgreSQL 先定位指定 SKU 的 BACKORDERED 訂單；再放 backordered_since，
-- 讓較早進入 backorder 的訂單排在前面，直接支援 FIFO，減少額外排序。
-- 若多筆訂單的 backordered_since 相同，最後以 id ASC 作為 tie-breaker，
-- 確保每次查詢的順序一致。欄位順序不可任意交換，否則可能無法完整利用此 index。
CREATE INDEX idx_orders_backorder_fifo
    ON orders (sku, status, backordered_since, id);

-- 此 index 配合「最近訂單列表」查詢：
-- ORDER BY placed_at DESC, id DESC LIMIT ?
--
-- 欄位順序與方向都必須與 ORDER BY 完全一致，PostgreSQL 才能直接沿 index 取前 N 筆而
-- 不用排序整張表。壓測後這張表會累積數千至上萬筆，而列表是操作台的主要畫面，
-- 沒有這個 index 每次查詢都是全表掃描加排序，只為了取前 20 筆。
--
-- id DESC 是 tie-breaker，不可省略：同一毫秒寫入的多筆訂單若沒有穩定次序，
-- 重複查詢會回傳不同順序，畫面上的列表就會無故跳動。理由與上方 FIFO index 相同。
CREATE INDEX idx_orders_recent
    ON orders (placed_at DESC, id DESC);
