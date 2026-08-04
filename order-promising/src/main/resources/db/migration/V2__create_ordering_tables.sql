-- 訂單層的八張表：貨主、商品主檔、倉庫主檔、位置、訂單、訂單行。
--
-- 建表順序固定為 owners → products → skus → facilities → stock_locations →
-- owner_facilities → orders → order_lines，FK 的被指向方一律在前。這個順序不可任意調換。
--
-- stock_locations 排在這裡而不是自己一個 migration：它同時被 V3 的庫存與本檔的 owner_facilities
-- 之後各表指向，必須在兩者之前。另開一個較晚的版本會讓被指向方排在指向方後面。
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
-- 一條會失敗的測試一起進來。同樣的判準也套用在 facilities 上。
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
-- 加一個沒有讀者的欄位比缺一個糟，因為下一個人會假設它有意義。orders.requested_facility_id
-- 就是前車之鑑：它帶著「R6 才讀」的註解存在了一整個 change，而 R6 沒有發生。
CREATE TABLE facilities (
    id UUID PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    CONSTRAINT uq_facilities_code UNIQUE (code)
);

-- 位置：搬運的端點。
--
-- 庫存掛在位置上而不是倉上，因為**倉當不了搬運的端點**——「供應商」與「客戶」不是本系統
-- 經營的倉，卻必須是移動的合法另一端，否則入庫與出庫表達不出來：
--
--   入庫      Vendors ──────────────> 某倉/庫存
--   出庫      某倉/庫存 ─────────────> Customers
--   盤盈虧    Inventory adjustment ─> 某倉/庫存
--
-- 三者都是位置之間的移動，全域總量因此守恆。詳見 docs/dom-stock-movement-scope.md。
--
-- **虛擬位置在此階段沒有任何讀者**——還沒有東西移動貨。現在就建，是因為 usage 的值域必須
-- 一次定完：晚一步引入等於同時改 CHECK 約束與回頭補種子資料，把兩個獨立的失效模式放進
-- 同一次改動。參考資料多一列的成本是零，欄位多一個的成本是每個讀取端都要處理它。
--
-- ---------------------------------------------------------------------------------------
-- 與 Odoo `stock_location` 的欄位對照：本表只有五欄，而 Odoo 有二十餘欄。下一個拿 Odoo
-- schema 來比對的人會逐欄問「為什麼沒有」，因此把「沒有」分成四類寫在這裡。
--
-- (1) 已有等價物
--     complete_name  →  本表的 code 就是 'WH-NORTH/Stock'，路徑已編在名字裡。Odoo 需要
--                       它是因為有樹要組路徑；沒有樹，一個欄位就到位。
--
-- (2) 需要樹才有意義
--     location_id（parent）、parent_path
--                    →  見下方「刻意沒有 parent_id 與 parent_path」。
--
-- (3) 需要本系統沒有的功能
--     cyclic_inventory_frequency / last_inventory_date / next_inventory_date
--                    →  循環盤點。系統裡沒有盤點流程。
--     storage_category_id / putaway_rule_ids
--                    →  上架規則的容量與相容性否決權。沒有 putaway。
--     replenish_location
--                    →  標記「補貨規則的目標位置」。沒有 reordering rule。
--     barcode        →  掃描作業面。沒有。
--     create_uid / write_uid / create_date / write_date
--                    →  Odoo 每張表都掛這四欄；本 repo 不做通用稽核欄位，
--                       facilities 也沒有。要加是整個 schema 一起加。
--
-- (4) **與 3PL 的定位牴觸**——這一類最容易被當成遺漏而「補上」
--     company_id     →  Odoo 的隔離維度是法人；我們是貨主，而一個位置本來就服務多個
--                       貨主——那是 3PL 的定義性特徵，不是要修掉的缺陷。
--     valuation_account_id
--                    →  **3PL 不擁有貨，永遠不對它持有的東西估值。** Odoo 有存貨估值
--                       是因為貨是它的；在這裡那是貨主帳上的事。
--
-- 唯一日後可能翻案的是 removal_strategy_id（Odoo 可逐位置設 FIFO/LIFO/FEFO）。現在 FEFO
-- 寫死在查詢與 idx_stock_pools_fefo 裡。**翻案的條件不是「想換策略」，而是同一套系統同時
-- 需要兩種取貨順序**（例如某貨主 FEFO、另一個指定批號）。在那之前，一個恆為 FEFO 的欄位
-- 只會讓人以為它可設定。
-- ---------------------------------------------------------------------------------------
CREATE TABLE stock_locations (
    id UUID PRIMARY KEY,
    -- 可空：虛擬位置不屬於任何倉。
    --
    -- 這一欄是實體欄位而不是沿樹推導。Odoo 的 stock.location.facility_id 也是 computed
    -- 但 store=True——它走過「查詢時算」再改成「存欄位」這條路，因為每次規則查找都要讀它。
    -- 本系統不做樹，這一欄直接就是答案。
    facility_id UUID,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    -- internal 才算公司庫存；其餘三種是虛擬位置，只用來當搬運的另一端。
    --
    -- 用 CHECK 而不是外鍵指向分類表：這四個值不是設定，是程式邏輯的分支。可設定的值域會讓
    -- 「新增第五種用途」看起來像資料維護，實際上每個分支都要跟著改。Odoo 同樣以 selection
    -- 表達而非關聯表。
    usage VARCHAR(32) NOT NULL,

    CONSTRAINT uq_stock_locations_code UNIQUE (code),
    -- 讓別的表能以複合外鍵「只准指向某一種用途的位置」。stock_pools 用它來保證庫存只掛在
    -- INTERNAL 位置上——CHECK 做不到那件事（不能有子查詢），而外鍵可以。
    CONSTRAINT uq_stock_locations_id_usage UNIQUE (id, usage),
    CONSTRAINT fk_stock_locations_warehouse
        FOREIGN KEY (facility_id) REFERENCES facilities(id),
    CONSTRAINT ck_stock_locations_usage
        CHECK (usage IN ('INTERNAL', 'SUPPLIER', 'CUSTOMER', 'INVENTORY')),
    -- 兩個方向都要擋。只擋一邊時，另一邊的髒資料會安靜地存在——「有倉的虛擬位置」會讓
    -- 「這個倉有哪些位置」多出一個不該在的答案，而那個錯誤不會有任何路徑報錯。
    CONSTRAINT ck_stock_locations_warehouse_by_usage CHECK (
        (usage =  'INTERNAL' AND facility_id IS NOT NULL)
     OR (usage <> 'INTERNAL' AND facility_id IS NULL)
    )

    -- **刻意沒有 parent_id 與 parent_path。**
    --
    -- 樹在 Odoo 的用途是儲區階層與「沿樹往上找到所屬倉」，而本系統一倉一位置，facility_id
    -- 直接就是答案，沒有查詢會沿樹走。日後要加收貨暫存或出貨暫存區時，orders 已經指倉、
    -- stock_pools 已經指位置，兩者都不用動，只是多幾列位置加上一個 parent_id。
    --
    -- **刻意沒有 active。** Odoo 建倉時把 Input／QC／Output／Packing 全建出來、靠 active
    -- 切換收發貨步數。本系統不做多步，加一個恆為 true 的欄位等於讓每個讀取端多處理一個
    -- 不會發生的狀態——與 facilities 拒絕 status 的判準相同。
);

-- 一個倉最多一個 internal 位置。
--
-- WHERE 子句不可省略：虛擬位置的 facility_id 為 NULL，而 PostgreSQL 把 NULL 視為互不相同，
-- 少了它三個虛擬位置仍然建得起來——所以拿掉不會立刻壞，會在「某個倉不小心有兩個庫存位置」
-- 時才壞，而那時倉→位置的解析會從一次查表變成不定的選擇。
CREATE UNIQUE INDEX uq_stock_locations_internal_per_warehouse
    ON stock_locations (facility_id) WHERE usage = 'INTERNAL';

-- 貨主與倉庫的多對多指派。一個貨主可以從多個倉出貨，一個倉服務多個貨主——後者是 3PL 的
-- 定義性特徵。
--
-- 這張表刻意沒有設定欄位。「這個貨主的貨放在這個倉」在 R3 之後會被庫存列的存在隱含表達，
-- 因此它不是授權表；它值得存在，是因為收單時要知道「這個貨主能指定哪些倉」，以及下方
-- orders 的複合外鍵要指向它。可否換批號之類的設定屬 R3，屆時加欄位即可，不動這個鍵。
--
-- 純粹的關係，沒有自己的身分，因此用複合主鍵而非代理鍵——與 products／skus 用代理鍵的
-- 判準一致，那兩者是有身分的實體。
CREATE TABLE owner_facilities (
    owner_id UUID NOT NULL,
    facility_id UUID NOT NULL,
    PRIMARY KEY (owner_id, facility_id),
    CONSTRAINT fk_owner_facilities_owner FOREIGN KEY (owner_id) REFERENCES owners(id),
    CONSTRAINT fk_owner_facilities_facility FOREIGN KEY (facility_id) REFERENCES facilities(id)
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
    -- 原名 requested_facility_id 且可空，那是選點時代的語意——「貨主提出的請求，可能被選點
    -- 推翻」。沒有選點之後它就是這張單的倉別，因此改名並改為 NOT NULL：可空等於在型別上
    -- 保留一個永遠不會發生的狀態，而每個讀取端都得處理它。
    facility_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL,
    -- 我們收到並接受這張單的時刻。由本系統寫入，呼叫端不得提供。
    --
    -- 這個欄位原名 placed_at，但值一直是收單時的 Instant.now()——名字說的是客戶下單，
    -- 存的卻是我們收單。凡是需要「訂單先後」的地方（缺貨佇列、最近訂單列表）用的都是
    -- 這個值，因為在一張單抵達之前，系統對它一無所知，無從為它保留任何東西。
    received_at TIMESTAMPTZ NOT NULL,
    -- 上游系統說客戶下單的時刻。可空——上游沒有義務送這個值。
    --
    -- 與 received_at 分開存，因為兩者回答不同的問題：一個是客戶何時承諾，一個是我們何時
    -- 有能力行動。在 3PL 兩者經常不同（上游批次送單、失敗重試、批次重跑），合成一欄就
    -- 分不出延遲來自上游還是來自我們。
    --
    -- 刻意不參與任何排序：它可空，而且由一個我們控制不了時鐘與送單排程的系統決定——
    -- 一張遲到的單會因此排到已經等候多時的單前面。
    placed_at TIMESTAMPTZ,
    allocated_at TIMESTAMPTZ,
    backordered_since TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_orders_owner FOREIGN KEY (owner_id) REFERENCES owners(id),
    -- 複合外鍵，不是單欄指向 facilities。它讓「倉存在，但這個貨主沒掛這個倉」
    -- 由資料庫擋下，而不是只擋得住「倉不存在」——與 order_lines 的 (owner_id, sku_code)
    -- 走自然鍵外鍵是同一個手法：把貨主放進參照，跨貨主的錯誤組合就無法寫入。
    --
    -- 這偏離了「外部來的值不建 FK」的原則（ship_to_zone 就沒有）。倉別不同於地址：地址
    -- 千變萬化，倉別是簽約時就固定的少數幾個值，上游送錯是設定錯誤而非資料多樣性。
    --
    -- fk_orders_owner 因此在邏輯上是多餘的（owner_facilities.owner_id 已指向 owners），
    -- 保留它是為了讓「訂單有貨主」這件事獨立於倉庫指派而成立。
    CONSTRAINT fk_orders_owner_node
        FOREIGN KEY (owner_id, facility_id)
        REFERENCES owner_facilities(owner_id, facility_id),
    -- 從 R5 提前。R5 真正的工作是 usecase 行為（重送時回傳既有訂單），但 constraint 只有
    -- 一行且屬同一次 migration。提前的價值在於此階段到 R5 之間，把「靜默建立重複訂單」
    -- 變成「明確報錯」——在倉儲場景前者是資料事故，後者只是錯誤訊息。
    CONSTRAINT uq_orders_owner_external_no UNIQUE (owner_id, external_order_no)
);

-- 此 index 配合「最近訂單列表」查詢：
-- ORDER BY received_at DESC, id DESC LIMIT ?
--
-- 排序鍵是收單時刻而不是上游的下單時刻：後者可空，且由上游的時鐘決定，一張遲到的單
-- 會排進早就收到的單之間，列表因此不再是「最近收到的」。
--
-- 欄位順序與方向都必須與 ORDER BY 完全一致，PostgreSQL 才能直接沿 index 取前 N 筆而
-- 不用排序整張表。壓測後這張表會累積數千至上萬筆，而列表是操作台的主要畫面，
-- 沒有這個 index 每次查詢都是全表掃描加排序，只為了取前 20 筆。
--
-- id DESC 是 tie-breaker，不可省略：同一毫秒寫入的多筆訂單若沒有穩定次序，
-- 重複查詢會回傳不同順序，畫面上的列表就會無故跳動。
CREATE INDEX idx_orders_recent
    ON orders (received_at DESC, id DESC);

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
    -- **刻意沒有 status。** 它曾經在這裡，理由是「REST 逐行揭露，放寬多行之後畫面不必改
    -- 契約就能逐行顯示」——那個理由不成立：ship-complete 保證一張單的所有行同進同出，多行
    -- 之後那些值仍然恆等於 header。它不是反正規化，是同一份資料存兩次。
    --
    -- REST 仍然逐行揭露 status，改由 header 導出。值一個字沒變，前端因此不動；差別在它從
    -- 「存起來的第二份真相」變成「讀取時的組合」。
    --
    -- 同理沒有 backordered_since，也沒有 allocated_at。兩者都會恆等於 header，而都沒有
    -- 讀取者。
    --
    -- backordered_since 曾經在這裡，理由寫的是「為了建出單表 FIFO index」——但那個查詢從來
    -- 就不是單表：它 join orders，篩選用行的 owner_id 與 sku_code，排序取自 header。欄位因此
    -- 從未被讀到，index 的排序段也從未被探到。排序鍵改用 order_id 之後（見下方 index），
    -- 連那個理由的形狀都不存在了。
    CONSTRAINT uq_order_lines_order_line_no UNIQUE (order_id, line_no),
    CONSTRAINT fk_order_lines_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT fk_order_lines_sku
        FOREIGN KEY (owner_id, sku_code) REFERENCES skus(owner_id, sku_code),
    CONSTRAINT ck_order_lines_quantity_positive CHECK (quantity > 0)
);

-- 此複合 index 配合待配需求的佇列查詢：
-- WHERE owner_id = ? AND sku_code = ?
-- ORDER BY order_id
--
-- **排序鍵是 order_id，因為它是 UUID v7**（見 IdGenerator）：時間戳編在主鍵裡，所以它的
-- 大小順序就是訂單進入系統的順序，也就是 FIFO 要的順序。不需要任何時間欄位，也不需要
-- tie-breaker——主鍵本身就唯一。
--
-- 為什麼是「進入系統的順序」而不是別的：在一張單抵達之前，系統對它一無所知，不可能為它
-- 保留任何東西。上游給的下單時刻（orders.placed_at）不能拿來排——它可空，而且由一個我們
-- 控制不了時鐘與送單排程的系統決定，一張三天前下單、今天才同步過來的單會插到已經等候
-- 一天的單前面。
--
-- 這一支取代的舊 index 第三欄是 order_lines.backordered_since，而那個欄位從來沒被查詢
-- 讀到——舊查詢的排序取自 orders，跨表因此探不到這裡的排序段。欄位數相同，只是換成一個
-- 真的會被用上的排序鍵。
--
-- 含 owner_id 是必要的：不同貨主的佇列必須分開排序，A 貨主的單不應該被 B 貨主的單卡住。
--
-- 刻意不含 status：待配佇列不能依 status 過濾——ordering 的配貨狀態落後於 allocation 的
-- 決策，拿它當閘門會重複預留。「還欠什麼」由 demand_lines 對 stock_reservations 的
-- NOT EXISTS 決定。
--
-- **倉別不在這支 index 裡**，因為它在 orders 上：查詢會 join 之後才過濾掉別的倉。要不要
-- 把倉別物化到行上換取完全覆蓋，留待有實際查詢計畫與資料量之後再決定。
CREATE INDEX idx_order_lines_demand_fifo
    ON order_lines (owner_id, sku_code, order_id);
