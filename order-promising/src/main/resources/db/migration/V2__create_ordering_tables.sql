-- 訂單層的七張表：貨主、商品主檔、倉庫主檔、訂單、訂單行。
--
-- 建表順序固定為 owners → products → skus → fulfillment_nodes → owner_nodes →
-- orders → order_lines，FK 的被指向方一律在前。這個順序不可任意調換。
--
-- 本檔原本只建 orders，且 orders 直接持有 sku 與 quantity。此 schema 尚未部署至任何
-- 環境，因此改寫為最終形狀而非以 ALTER 疊加——否則 migration 歷史會記錄一段「建了又砍」
-- 的假歷史，而 orders.owner_id 的被指向方 owners 也會比它晚建。代價是既有的 Postgres
-- volume 必須移除後重建（Flyway checksum 不符），不得以 flyway repair 略過。
--
-- 本檔原為 V3、stock_pools 為 V2。庫存加上指向 skus 的外鍵之後，被指向方必須先建，
-- 因此兩者對調。同樣因為未部署，對調編號比在後續 migration 用 ALTER 補外鍵乾淨——
-- 後者會讓 stock_pools 的定義散在兩個檔案裡。

-- 貨主只有身分。沒有 status——停用貨主在營運上是真的，但本階段沒有任何決策讀它，而一個
-- 沒有讀者的欄位會讓下一個人以為它有意義。等收單真的要擋停用貨主時再加，屆時它會帶著
-- 一條會失敗的測試一起進來。同樣的判準也套用在 fulfillment_nodes 上。
CREATE TABLE owners (
    id UUID PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    CONSTRAINT uq_owners_code UNIQUE (code)
);

-- 商品分「款」（products）與「規格」（skus）兩層。溫層屬款、重量屬規格。
--
-- 溫層放在款層級，是為了讓「同款兩種溫層」這類髒資料在結構上無法產生——那不是無效值
-- 而是無效組合，收單時不會報錯，會拖到有人依溫層篩選時才以「同一款商品被分到不同結果」
-- 的形式浮現，屆時很難歸因。這個結構價值不依賴目前有沒有讀者。
-- 主檔用代理鍵，與本 schema 其餘各表一致；「同一貨主的款號唯一、不同貨主可以撞號」
-- 由 unique constraint 保證，與主鍵是誰無關。
CREATE TABLE products (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    product_code VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    temperature_zone VARCHAR(32) NOT NULL,
    CONSTRAINT uq_products_owner_code UNIQUE (owner_id, product_code),
    CONSTRAINT fk_products_owner FOREIGN KEY (owner_id) REFERENCES owners(id),
    CONSTRAINT ck_products_temperature_zone
        CHECK (temperature_zone IN ('AMBIENT', 'CHILLED', 'FROZEN'))
);

-- 同上用代理鍵。但外鍵刻意仍走自然鍵 (owner_id, product_code) 而非 products.id：
-- 在 3PL 裡編碼由貨主自訂、跨貨主必然撞號，走自然鍵的外鍵會強制每一次參照都帶上貨主，
-- 而「款與規格必須屬於同一個貨主」這件事因此由資料庫保證，不需在應用層檢查。
-- PostgreSQL 允許外鍵指向 unique constraint 而不必是主鍵。
CREATE TABLE skus (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    sku_code VARCHAR(64) NOT NULL,
    product_code VARCHAR(64) NOT NULL,
    spec_name VARCHAR(255) NOT NULL,
    -- 重量在規格層級：500ml 與 1L 不同。原為選點成本函數的運費基準，該用途已隨
    -- sourcing 移出範圍；欄位保留，它是規格的事實。
    weight_gram INTEGER NOT NULL,
    CONSTRAINT uq_skus_owner_code UNIQUE (owner_id, sku_code),
    CONSTRAINT fk_skus_product
        FOREIGN KEY (owner_id, product_code) REFERENCES products(owner_id, product_code),
    CONSTRAINT ck_skus_weight_positive CHECK (weight_gram > 0)
);

-- 「列出某款的所有規格」是下單表單第二段選擇的查詢。skus 的主鍵是代理鍵、unique
-- constraint 是 (owner_id, sku_code)，兩者都涵蓋不到 (owner_id, product_code)，
-- 因此需要這個 index。它同時支撐 fk_skus_product 的完整性檢查。
CREATE INDEX idx_skus_product
    ON skus (owner_id, product_code);

-- 倉庫只有身分，沒有屬性。
--
-- 沒有 status、type、覆蓋範圍、處理能力、產能或截單時間——那些欄位全都是為了「系統選倉」
-- 這個決策而存在，而本系統不做那件事：3PL 的出貨倉由合約決定，貨主在上游下單時就指定了。
-- 加一個沒有讀者的欄位比缺一個糟，因為下一個人會假設它有意義。orders.requested_node_id
-- 就是前車之鑑：它帶著「R6 才讀」的註解存在了一整個 change，而 R6 沒有發生。
CREATE TABLE fulfillment_nodes (
    id UUID PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    CONSTRAINT uq_fulfillment_nodes_code UNIQUE (code)
);

-- 貨主與倉庫的多對多指派。一個貨主可以從多個倉出貨，一個倉服務多個貨主——後者是 3PL 的
-- 定義性特徵。
--
-- 這張表刻意沒有設定欄位。「這個貨主的貨放在這個倉」在 R3 之後會被庫存列的存在隱含表達，
-- 因此它不是授權表；它值得存在，是因為收單時要知道「這個貨主能指定哪些倉」，以及下方
-- orders 的複合外鍵要指向它。可否換批號之類的設定屬 R3，屆時加欄位即可，不動這個鍵。
--
-- 純粹的關係，沒有自己的身分，因此用複合主鍵而非代理鍵——與 products／skus 用代理鍵的
-- 判準一致，那兩者是有身分的實體。
CREATE TABLE owner_nodes (
    owner_id UUID NOT NULL,
    node_id UUID NOT NULL,
    PRIMARY KEY (owner_id, node_id),
    CONSTRAINT fk_owner_nodes_owner FOREIGN KEY (owner_id) REFERENCES owners(id),
    CONSTRAINT fk_owner_nodes_node FOREIGN KEY (node_id) REFERENCES fulfillment_nodes(id)
);

CREATE TABLE orders (
    id UUID PRIMARY KEY,
    -- 決定可動用哪批庫存。跨貨主隔離要等 R3——stock_pools 目前還沒有 owner_id。
    owner_id UUID NOT NULL,
    external_order_no VARCHAR(128) NOT NULL,
    -- 完整地址供履約與面單使用；分區是地址的粗粒度形式。
    -- 地址內嵌於此而不另開 addresses 表：地址逐單指定、不可重用，獨立表只會多一層 join。
    ship_to_zone VARCHAR(32) NOT NULL,
    ship_to_address VARCHAR(512) NOT NULL,
    promised_delivery_date DATE NOT NULL,
    -- 這張單從哪個倉出。值由貨主的上游系統在收單時給定，系統不推導、不預設、不改。
    -- 原名 requested_node_id 且可空，那是選點時代的語意——「貨主提出的請求，可能被選點
    -- 推翻」。沒有選點之後它就是這張單的倉別，因此改名並改為 NOT NULL：可空等於在型別上
    -- 保留一個永遠不會發生的狀態，而每個讀取端都得處理它。
    fulfillment_node_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL,
    placed_at TIMESTAMPTZ NOT NULL,
    allocated_at TIMESTAMPTZ,
    backordered_since TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_orders_owner FOREIGN KEY (owner_id) REFERENCES owners(id),
    -- 複合外鍵，不是單欄指向 fulfillment_nodes。它讓「倉存在，但這個貨主沒掛這個倉」
    -- 由資料庫擋下，而不是只擋得住「倉不存在」——與 order_lines 的 (owner_id, sku_code)
    -- 走自然鍵外鍵是同一個手法：把貨主放進參照，跨貨主的錯誤組合就無法寫入。
    --
    -- 這偏離了「外部來的值不建 FK」的原則（ship_to_zone 就沒有）。倉別不同於地址：地址
    -- 千變萬化，倉別是簽約時就固定的少數幾個值，上游送錯是設定錯誤而非資料多樣性。
    --
    -- fk_orders_owner 因此在邏輯上是多餘的（owner_nodes.owner_id 已指向 owners），
    -- 保留它是為了讓「訂單有貨主」這件事獨立於倉庫指派而成立。
    CONSTRAINT fk_orders_owner_node
        FOREIGN KEY (owner_id, fulfillment_node_id)
        REFERENCES owner_nodes(owner_id, node_id),
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
    -- 刻意沒有倉別欄位。一張單只從一個倉出、明細不可跨倉，因此行上的倉別會恆等於 header，
    -- 是純重複而非反正規化。對照上方的 owner_id：那個複製有獨立理由——(owner_id, sku_code)
    -- 才是 skus 的自然鍵，沒有它就建不出外鍵。
    --
    -- 放寬多行之後仍然如此：多行是一張單有多個 SKU，不是多個倉。
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
