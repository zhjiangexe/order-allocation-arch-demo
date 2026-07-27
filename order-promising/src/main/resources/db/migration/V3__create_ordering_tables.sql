-- 訂單層的五張表：貨主與商品主檔、訂單、訂單行。
--
-- 建表順序固定為 owners → products → skus → orders → order_lines，FK 的被指向方一律
-- 在前。這個順序不可任意調換。
--
-- 本檔原本只建 orders，且 orders 直接持有 sku 與 quantity。此 schema 尚未部署至任何
-- 環境，因此改寫為最終形狀而非以 ALTER 疊加——否則 migration 歷史會記錄一段「建了又砍」
-- 的假歷史，而 orders.owner_id 的被指向方 owners 也會比它晚建。代價是既有的 Postgres
-- volume 必須移除後重建（Flyway checksum 不符），不得以 flyway repair 略過。

CREATE TABLE owners (
    id UUID PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    -- 是否允許一張單跨節點拆單。3PL 簽約時定的服務條款，非逐單決定；R6 讀它。
    allow_split_shipment BOOLEAN NOT NULL,
    CONSTRAINT uq_owners_code UNIQUE (code),
    CONSTRAINT ck_owners_status CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);

-- 商品分「款」（products）與「規格」（skus）兩層。溫層屬款、重量屬規格。
--
-- 溫層放在款層級，是為了讓「同款兩種溫層」這類髒資料在結構上無法產生——那不是無效值
-- 而是無效組合，收單時不會報錯，會拖到 R6 依節點 capabilities 篩選時才以「同一款商品
-- 被篩到不同節點」的形式浮現，屆時很難歸因。
CREATE TABLE products (
    owner_id UUID NOT NULL,
    product_code VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    temperature_zone VARCHAR(32) NOT NULL,
    CONSTRAINT pk_products PRIMARY KEY (owner_id, product_code),
    CONSTRAINT fk_products_owner FOREIGN KEY (owner_id) REFERENCES owners(id),
    CONSTRAINT ck_products_temperature_zone
        CHECK (temperature_zone IN ('AMBIENT', 'CHILLED', 'FROZEN'))
);

-- PK 為 (owner_id, sku_code) 而非代理鍵：在 3PL 裡 sku_code 由貨主自訂，不同貨主必然
-- 撞號，「SKU-A」單獨存在時不指向任何東西。複合鍵讓這個事實出現在型別上——所有要指向
-- SKU 的地方都被迫同時帶上 owner_id。代理鍵做得到同樣的完整性，但允許程式碼只帶
-- sku_code 到處跑，而那正是本次改造前的問題。
CREATE TABLE skus (
    owner_id UUID NOT NULL,
    sku_code VARCHAR(64) NOT NULL,
    product_code VARCHAR(64) NOT NULL,
    spec_name VARCHAR(255) NOT NULL,
    -- R6 成本函數的運費基準。500ml 與 1L 重量不同，因此在規格層級。
    weight_gram INTEGER NOT NULL,
    CONSTRAINT pk_skus PRIMARY KEY (owner_id, sku_code),
    CONSTRAINT fk_skus_product
        FOREIGN KEY (owner_id, product_code) REFERENCES products(owner_id, product_code),
    CONSTRAINT ck_skus_weight_positive CHECK (weight_gram > 0)
);

-- 「列出某款的所有規格」是下單表單第二段選擇的查詢。skus 的 PK 是
-- (owner_id, sku_code)，涵蓋不到 (owner_id, product_code)，因此需要這個 index。
CREATE INDEX idx_skus_product
    ON skus (owner_id, product_code);

CREATE TABLE orders (
    id UUID PRIMARY KEY,
    -- 決定可動用哪批庫存。跨貨主隔離要等 R3——stock_pools 目前還沒有 owner_id。
    owner_id UUID NOT NULL,
    external_order_no VARCHAR(128) NOT NULL,
    -- 配送分區是 R6 的決策輸入；完整地址供履約與面單使用，sourcing 不看。
    -- 地址內嵌於此而不另開 addresses 表：地址逐單指定、不可重用，獨立表只會多一層 join。
    ship_to_zone VARCHAR(32) NOT NULL,
    ship_to_address VARCHAR(512) NOT NULL,
    promised_delivery_date DATE NOT NULL,
    -- 貨主指定的出貨倉，指定則 R6 跳過選點。此階段只收下不使用。
    -- 刻意不建 FK：被指向的 fulfillment_nodes 要等 R2。
    requested_node_id UUID,
    status VARCHAR(32) NOT NULL,
    placed_at TIMESTAMPTZ NOT NULL,
    allocated_at TIMESTAMPTZ,
    backordered_since TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_orders_owner FOREIGN KEY (owner_id) REFERENCES owners(id),
    -- 從 R5 提前。R5 真正的工作是 usecase 行為（重送時回傳既有訂單），但 constraint 只有
    -- 一行且屬同一次 migration。提前的價值在於此階段到 R5 之間，把「靜默建立重複訂單」
    -- 變成「明確報錯」——在倉儲場景前者是資料事故，後者只是錯誤訊息。
    CONSTRAINT uq_orders_owner_external_no UNIQUE (owner_id, external_order_no)
);

-- 此 index 配合「最近訂單列表」查詢：
-- ORDER BY placed_at DESC, id DESC LIMIT ?
--
-- 欄位順序與方向都必須與 ORDER BY 完全一致，PostgreSQL 才能直接沿 index 取前 N 筆而
-- 不用排序整張表。壓測後這張表會累積數千至上萬筆，而列表是操作台的主要畫面，
-- 沒有這個 index 每次查詢都是全表掃描加排序，只為了取前 20 筆。
--
-- id DESC 是 tie-breaker，不可省略：同一毫秒寫入的多筆訂單若沒有穩定次序，
-- 重複查詢會回傳不同順序，畫面上的列表就會無故跳動。
CREATE INDEX idx_orders_recent
    ON orders (placed_at DESC, id DESC);

CREATE TABLE order_lines (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    -- 上游單的行號，保留原始結構。
    line_no INTEGER NOT NULL,
    -- owner_id 反正規化自 header。值不可變（一張單的貨主不會改變），因此沒有同步成本；
    -- 它的直接用途是 FK——(owner_id, sku_code) 才建得出外鍵，否則 SKU 的完整性只能在
    -- 應用層檢查。
    owner_id UUID NOT NULL,
    sku_code VARCHAR(64) NOT NULL,
    quantity INTEGER NOT NULL,
    -- R6 的決策輸出：實際出貨節點。此階段恆為空，且刻意不建 FK（fulfillment_nodes 要等
    -- R2）。放在 line 而非 header，是因為拆單後不同 line 可能從不同節點出。
    assigned_node_id UUID,
    status VARCHAR(32) NOT NULL,
    -- 這一行進入缺貨的時間。從 R8 提前，理由是 index 而非領域事實：採 ship-complete 後
    -- 所有 line 在同一交易內一起配到或一起缺貨，所以此欄恆等於 header 的值。它在這裡
    -- 純粹是為了建出下方的單表 FIFO index。
    --
    -- 對應地不建 line 層級的 allocated_at：同樣恆等於 header，但沒有任何 index 需要它。
    backordered_since TIMESTAMPTZ,
    CONSTRAINT uq_order_lines_order_line_no UNIQUE (order_id, line_no),
    CONSTRAINT fk_order_lines_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT fk_order_lines_sku
        FOREIGN KEY (owner_id, sku_code) REFERENCES skus(owner_id, sku_code),
    CONSTRAINT ck_order_lines_quantity_positive CHECK (quantity > 0)
);

-- 此複合 index 配合待配佇列查詢：
-- WHERE owner_id = ? AND sku_code = ?
-- ORDER BY backordered_since ASC, id ASC
--
-- 從 R8 提前。不提前就沒有 FIFO index 可用——篩選鍵（owner_id、sku_code）在 line、
-- 排序鍵在 header，橫跨兩張表的「篩選 ＋ 排序」無法用單一複合 index 覆蓋。留在 R8 等於
-- 此階段到 R8 全程無 index，壓測基準會斷掉且無法歸因。
--
-- 欄位順序沿用等值篩選在前、排序鍵其次、id 作為 tie-breaker 的原則。含 owner_id 是必要
-- 的：不同貨主的 backorder 隊列必須分開排序，A 貨主的單不應該被 B 貨主的單卡住。
--
-- 刻意不含 status，這是與被它取代的 idx_orders_backorder_fifo 唯一的實質差異。舊查詢是
-- WHERE status = 'BACKORDERED'，但 R4 之後待配佇列不能依 status 過濾——ordering 的配貨
-- 狀態落後於 allocation 的決策，拿它當閘門會重複預留。而 status 若夾在 sku_code 與
-- backordered_since 之間，index 掃出的列會先按 status 分組再按時間排序，查詢不篩 status
-- 時 PostgreSQL 仍得排序一次，index 等於白建。
CREATE INDEX idx_order_lines_backorder_fifo
    ON order_lines (owner_id, sku_code, backordered_since, id);
