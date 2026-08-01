-- 庫存。**一列是一批貨，不是一個 SKU 的池。**
--
-- 這張表就是 Odoo 的 `stock.quant`：某個位置上一群可互換單位的餘額。兩個詞說的是同一件事
-- ——`pool` 的定義是「一群可互換的單位」，而那正是 quant 的定義，因此不改名。逐欄對照：
--
--   本系統                Odoo stock.quant     說明
--   owner_id              owner_id             相同
--   location_id           location_id          相同
--   sku_code              product_id           自然鍵風格，與本 schema 其餘各表一致
--   in_date               in_date              **Odoo 也是這個名字**，同樣用於取貨排序
--   expiry_date           （在 stock.lot 上）   本系統不做 lot，效期留在本列
--   reserved_quantity     reserved_quantity    相同
--   on_hand_quantity      quantity             **保留本系統的名字**，見下
--
-- `on_hand_quantity` 不改名為 `quantity`：Odoo 該欄的 UI 標籤是 "Quantity On Hand"，欄位名是
-- 簡稱而語意是在手量。它旁邊就是 `reserved_quantity`，裸的 `quantity` 會變成「什麼的數量」。
-- 對齊業界詞彙的目的是讓人少推導一次，不是讓名字變短。
--
-- **批次身分（lot）不在這張表裡**，也不打算做——見 docs/dom-stock-movement-scope.md。這一點
-- 要寫下來，因為下一個對照 Odoo 的人很容易把本表推導成 stock.lot：兩者都帶效期，差別在
-- quant 是餘額、lot 是可追溯的批次身分。
--
-- **本表比 Odoo 多一條保證**：`stock.quant` 在 Odoo 19 沒有 unique index，唯一性靠事後
-- `_merge_quants` 的 GROUP BY 合併。而下方的 `uq_stock_pools_batch` 是補貨「命中既有列就加量、
-- 否則新開一列」的前提，不能退成事後合併——那等於容忍一段期間的重複列，而那段期間 ATP 會
-- 被低估。
--
-- **餘額是物化的，不是 SUM(moves)。** 引入異動之後仍然如此：一列庫存是被異動寫出來的餘額，
-- 不是它們的檢視。守著那個餘額的樂觀鎖是補貨與喚醒序列化的機制，見 dom-promising-scope.md
-- 的「決定二」。
--
-- 本檔原為 V2、訂單層各表為 V3。加上指向 skus 的外鍵之後被指向方必須先建，因此對調。

CREATE TABLE stock_pools (
    id UUID PRIMARY KEY,

    -- 身分的五個維度。少任何一個都會讓不可互換的貨被合併：
    -- owner     兩個貨主的同碼 SKU 是不同的貨，不可互相調用
    -- location  貨實際在哪。一張單只從它的來源位置配貨
    -- sku       商品本身
    -- in_date   同效期的貨分兩次到貨仍是兩批，也是 FEFO 平手時的先後依據
    -- expiry    FEFO 的排序鍵，也決定這批過期了沒有
    owner_id UUID NOT NULL,
    -- 位置而不是倉。倉當不了搬運的端點——供應商與客戶不是本系統經營的倉，卻必須是移動的
    -- 合法另一端，否則入庫與出庫表達不出來。見 stock_locations 的檔頭。
    --
    -- 一倉一個內部位置時，按倉查與按位置查取到的是同一批貨，因此這一步不改變任何配貨行為。
    location_id UUID NOT NULL,
    sku_code VARCHAR(64) NOT NULL,
    in_date DATE NOT NULL,
    -- NOT NULL 而非可空：PostgreSQL 的 unique 把 NULL 視為互不相同，可空會讓同日到貨的
    -- 無效期商品各成一列而不是合併成一列，而那是安靜發生的。種子全是食品，NOT NULL 不
    -- 造成虛構；真的出現非效期商品時是改約束，不是改鍵。
    expiry_date DATE NOT NULL,

    -- 恆為 'INTERNAL'，唯一的讀者是下方的 fk_stock_pools_internal_location。
    --
    -- 「庫存只能掛在內部位置」用 CHECK 表達不了——那需要看另一張表，而 CHECK 不能有子查詢。
    -- 複合外鍵可以，但外鍵的欄位必須來自本表，所以要有這一欄。這與 order_lines.owner_id
    -- 是同一個手法：一個為了讓外鍵表達得出來而存在的欄位，值不可變、沒有同步成本。
    --
    -- 少了這條保證，「系統宣稱在一個它不經營的地方持有貨」寫得進去，而症狀要到總量對帳時
    -- 才出現。
    location_usage VARCHAR(32) NOT NULL DEFAULT 'INTERNAL',

    on_hand_quantity INTEGER NOT NULL,
    reserved_quantity INTEGER NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_stock_pools_batch
        UNIQUE (owner_id, location_id, sku_code, in_date, expiry_date),

    -- 外鍵走自然鍵 (owner_id, sku_code) 而非 skus.id，與 order_lines 同一個手法：在 3PL 裡
    -- SKU 代碼跨貨主撞號，帶著貨主的參照才檢查得了。少了它，打錯代碼會產生「有庫存卻永遠
    -- 配不到貨」的資料，而那要查很久才會歸因到打錯字。
    CONSTRAINT fk_stock_pools_sku
        FOREIGN KEY (owner_id, sku_code) REFERENCES skus(owner_id, sku_code),
    -- 複合外鍵，不是單欄指向 stock_locations.id。單欄只保證位置存在；帶上用途才保證它是
    -- 內部位置。被指向的是 stock_locations 的 uq_stock_locations_id_usage。
    CONSTRAINT fk_stock_pools_internal_location
        FOREIGN KEY (location_id, location_usage) REFERENCES stock_locations(id, usage),
    CONSTRAINT ck_stock_pools_location_usage_internal
        CHECK (location_usage = 'INTERNAL'),

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
-- WHERE owner_id = ? AND location_id = ? AND sku_code = ? AND expiry_date >= ?
-- ORDER BY expiry_date, in_date, id
--
-- 欄位順序與 ORDER BY 完全一致，PostgreSQL 才能沿 index 取列而不用排序。等值篩選在前、
-- 排序鍵其次、id 作為最後的 tie-breaker。這個結構的理由與「按倉還是按位置」無關，因此
-- 只有第二欄換了名字。
--
-- 排序鍵有三層不是裝飾：同效期不同日到貨很常見（同一生產批分兩車送到），少了 in_date 與
-- id，同效期的批之間順序不定，配貨結果就不可重現，而防死鎖的寫入排序也失去依據。
CREATE INDEX idx_stock_pools_fefo
    ON stock_pools (owner_id, location_id, sku_code, expiry_date, in_date, id);
