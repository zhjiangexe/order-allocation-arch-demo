-- 庫存。**一列是一批貨，不是一個 SKU 的池。**
--
-- 表名與領域類別名都還叫 stock_pool，但它承載的東西在此已經改變：從「該 SKU 的可用量」
-- 變成「某貨主在某倉、某日到貨、某效期的那一批」。名字與內容不符是刻意保留的（改名的
-- 取捨見 docs/dom-order-intake-scope.md 的「為何不改名為 stock_batches」），因此這裡必須
-- 寫下來——否則下一個人會照名字去理解，然後假設一個 SKU 只有一列。
--
-- 本檔原為 V2、訂單層各表為 V3。加上指向 skus 的外鍵之後被指向方必須先建，因此對調。

CREATE TABLE stock_pools (
    id UUID PRIMARY KEY,

    -- 身分的五個維度。少任何一個都會讓不可互換的貨被合併：
    -- owner   兩個貨主的同碼 SKU 是不同的貨，不可互相調用
    -- node    分倉存放，一張單只從它指定的倉配貨
    -- sku     商品本身
    -- in_date 同效期的貨分兩次到貨仍是兩批，也是 FEFO 平手時的先後依據
    -- expiry  FEFO 的排序鍵，也決定這批過期了沒有
    owner_id UUID NOT NULL,
    node_id UUID NOT NULL,
    sku_code VARCHAR(64) NOT NULL,
    in_date DATE NOT NULL,
    -- NOT NULL 而非可空：PostgreSQL 的 unique 把 NULL 視為互不相同，可空會讓同日到貨的
    -- 無效期商品各成一列而不是合併成一列，而那是安靜發生的。種子全是食品，NOT NULL 不
    -- 造成虛構；真的出現非效期商品時是改約束，不是改鍵。
    expiry_date DATE NOT NULL,

    on_hand_quantity INTEGER NOT NULL,
    reserved_quantity INTEGER NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_stock_pools_batch
        UNIQUE (owner_id, node_id, sku_code, in_date, expiry_date),

    -- 外鍵走自然鍵 (owner_id, sku_code) 而非 skus.id，與 order_lines 同一個手法：在 3PL 裡
    -- SKU 代碼跨貨主撞號，帶著貨主的參照才檢查得了。少了它，打錯代碼會產生「有庫存卻永遠
    -- 配不到貨」的資料，而那要查很久才會歸因到打錯字。
    CONSTRAINT fk_stock_pools_sku
        FOREIGN KEY (owner_id, sku_code) REFERENCES skus(owner_id, sku_code),
    CONSTRAINT fk_stock_pools_node
        FOREIGN KEY (node_id) REFERENCES fulfillment_nodes(id),

    CONSTRAINT ck_stock_pools_on_hand_non_negative CHECK (on_hand_quantity >= 0),
    CONSTRAINT ck_stock_pools_reserved_non_negative CHECK (reserved_quantity >= 0),
    CONSTRAINT ck_stock_pools_reserved_not_above_on_hand
        CHECK (reserved_quantity <= on_hand_quantity)

    -- **刻意沒有 CHECK (expiry_date >= in_date)。**
    --
    -- 看起來像缺了約束，其實不是：貨可能到貨時就已經過期——運輸延誤、上游出錯、長途轉運，
    -- 而倉庫實體上就是收到了那批貨。加上那個約束之後，要把它記進系統就得在兩個日期裡挑一個
    -- 造假，而帳與實體貨對不上比「資料看起來怪」糟得多；履約層兩本帳對帳的整個前提就是帳要
    -- 反映實體。
    --
    -- 到貨即過期只是「有貨但配不到」的極端情形，而那條路徑已經處理好了：`isExpired(today)`
    -- 回 true、`findAllocatableBatchesInFefoOrder` 濾掉它、庫存頁顯示 expired。
);

-- 配貨查詢：
-- WHERE owner_id = ? AND node_id = ? AND sku_code = ? AND expiry_date >= ?
-- ORDER BY expiry_date, in_date, id
--
-- 欄位順序與 ORDER BY 完全一致，PostgreSQL 才能沿 index 取列而不用排序。等值篩選在前、
-- 排序鍵其次、id 作為最後的 tie-breaker。
--
-- 排序鍵有三層不是裝飾：同效期不同日到貨很常見（同一生產批分兩車送到），少了 in_date 與
-- id，同效期的批之間順序不定，配貨結果就不可重現，而防死鎖的寫入排序也失去依據。
CREATE INDEX idx_stock_pools_fefo
    ON stock_pools (owner_id, node_id, sku_code, expiry_date, in_date, id);
