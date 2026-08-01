-- 搬運：單據、段落、落地。
--
-- 本檔取代原本的 stock_reservations。舊模型記的是搬運的一半——「這條行從這一批鎖了多少」，
-- 卻沒有記那批貨**要去哪裡**。出貨那一刻要補上的正是缺的那一半，而那時再改，等於把所有既有
-- 的預留重新詮釋一次。
--
-- 三張表是三種不同的東西，不是「單頭、明細、子明細」：
--
--   stock_pickings      一張倉庫作業單。**倉庫任務的真相**——沒有 SKU、沒有數量。
--   stock_moves         作業單中一個 SKU 的一段移動。**庫存數量的真相**。
--   stock_move_lines    那一段實際從哪一批取用。
--
-- 對照 Odoo：stock.picking / stock.move / stock.move.line。詳見
-- docs/dom-stock-movement-scope.md 的「picking 與 move 是兩種真相」。


-- 作業類型：這種搬運從哪到哪。
--
-- 它存在的理由是讓「新增一種流程」成為**新增一筆資料**而不是改程式。要擋的是流程種類被編碼
-- 成 enum 值那個病——既有系統的出庫類型長到 18 種、狀態欄位長到 28 個值，就是那樣累積的。
--
-- **但它不說「下一段是誰」。** 作業類型之間沒有任何「後繼」的欄位；把多段串起來是規則引擎的
-- 事，而本系統沒有多段。分界是：作業類型描述「一段作業長什麼樣」，規則描述「在什麼需求下、
-- 會有哪幾段、順序如何」。
--
-- **刻意沒有 sequence_code / sequence_id。** Odoo 用它們產生 WH/OUT/00001 這種單號；本系統
-- 不產生單號，picking 的 reference 存上游給的參照就夠。沒有讀者的欄位不建。
CREATE TABLE stock_picking_types (
    id UUID PRIMARY KEY,
    warehouse_id UUID NOT NULL,
    -- 進、出、內部調撥。Odoo 另有製造與維修，那些來自別的模組。
    code VARCHAR(32) NOT NULL,
    name VARCHAR(255) NOT NULL,
    -- 這種作業預設從哪到哪。建立 picking 時取用；picking 仍存自己的起訖，因為個別單據
    -- 可以不同——Odoo 也是兩邊都存，預設值與實際值是兩件事。
    default_from_location_id UUID NOT NULL,
    default_to_location_id UUID NOT NULL,

    CONSTRAINT uq_stock_picking_types_warehouse_code UNIQUE (warehouse_id, code),
    CONSTRAINT fk_stock_picking_types_warehouse
        FOREIGN KEY (warehouse_id) REFERENCES fulfillment_nodes(id),
    CONSTRAINT fk_stock_picking_types_from_location
        FOREIGN KEY (default_from_location_id) REFERENCES stock_locations(id),
    CONSTRAINT fk_stock_picking_types_to_location
        FOREIGN KEY (default_to_location_id) REFERENCES stock_locations(id),
    CONSTRAINT ck_stock_picking_types_code
        CHECK (code IN ('INBOUND', 'OUTBOUND', 'INTERNAL'))
);


-- 一張倉庫作業單。
--
-- **沒有 SKU、沒有數量、不直接影響庫存**——它只說「這一趟作業從哪到哪、屬於誰」。數量的真相
-- 在底下的 move。
--
-- **有 order_id，而它不是捷徑。**
--
-- Odoo 的 stock_picking.sale_id 確實是捷徑：它的 picking 會跨單合併，所以單頭指向的訂單與
-- 底下 move 指向的訂單行可能分屬不同的單，兩者不一致時沒有規則說該信誰。
--
-- **本系統不合併**（見上方作業類型的說明），一張出庫單就是一張 picking，因此這一欄是單據的
-- 身分而不是重複的參照。而它是**必要的**：配貨要做兩件事——ship-complete 的整單判斷、以及
-- 發出帶 orderId 的配貨結果事件——而 move 只有 order_line_id。從行推到單需要 join
-- order_lines，那正是邊界規則禁止的（執行層只准持有需求的 id，不准讀它的欄位）。
--
-- **但 ship-complete 的分組不用它，用 picking_id。** 單表 group by，補貨喚醒佇列這條熱路徑
-- 因此一個 join 都沒有；order_id 只在配到之後要發事件時才查，而那時只有少數幾張單。
-- picking_id 也是更自然的分組鍵——picking 的意思就是「這些 move 是同一份工作」，而
-- ship-complete 判斷的正是一份工作能不能整個完成。
--
-- 可空：第三個 change 的入庫單背後沒有任何訂單。
--
-- **若日後真的要跨單合併，這一欄必須拿掉**，分組改由一個有自己身分的群組實體承擔。屆時
-- 它就會退化成 Odoo 那種捷徑。
--
-- **刻意沒有 state。** Odoo 的 stock.picking.state 是由底下 moves 算出來的 computed 欄位，
-- store 只為了畫面篩選。ship-complete 下一張單的所有 move 同進同出，彙總是 trivial 的，而存
-- 起來就有兩份要對齊的真相。要存的那天是「picking 列表要依狀態篩選」，屆時它仍是導出值。
--
-- **刻意沒有 version。** 沒有併發寫入 picking 的路徑。會被搶的是庫存列，樂觀鎖在那裡。
--
-- 此外沒有：作業員（沒有現場作業）、波次（波次管理的是作業單，而還沒有作業）、backorder_id
-- （分批出貨屬 R7）、列印與簽收（沒有作業文件）、move_type（ship-complete 恆等於「全備妥
-- 才出」，一個永遠同值的欄位沒有讀者）。
CREATE TABLE stock_pickings (
    id UUID PRIMARY KEY,
    picking_type_id UUID NOT NULL,
    -- 貨主。3PL 的隔離維度，Odoo 沒有對應物（它的隔離維度是法人）。
    owner_id UUID NOT NULL,
    -- 這張單據為哪一張訂單而做。入庫時為空。理由見檔頭。
    --
    -- 刻意不建外鍵指向 orders：那會讓執行層的 schema 依賴需求層的表。完整性由 move 的
    -- order_line_id 外鍵保證——行存在就蘊含它的訂單存在。與舊 stock_reservations.order_id
    -- 的判斷相同。
    order_id UUID,
    -- 整單共同的起訖範圍。個別 move 可以不同——那正是多步作業的形狀。
    --
    -- **刻意不加「只能指向 INTERNAL 位置」的約束。** 那條約束在 stock_pools 上是對的（公司
    -- 持有的貨只存在於內部位置），照著複製到這裡會擋掉出庫本身——出庫的目的地就是 CUSTOMER
    -- 這個虛擬位置。庫存是「持有」，搬運是「移動」，而移動的一端經常在公司之外。
    from_location_id UUID NOT NULL,
    to_location_id UUID NOT NULL,
    -- 上游給的參照。不是本系統產生的單號。
    reference VARCHAR(128),
    scheduled_at TIMESTAMPTZ,

    CONSTRAINT fk_stock_pickings_type
        FOREIGN KEY (picking_type_id) REFERENCES stock_picking_types(id),
    CONSTRAINT fk_stock_pickings_owner FOREIGN KEY (owner_id) REFERENCES owners(id),
    CONSTRAINT fk_stock_pickings_from_location
        FOREIGN KEY (from_location_id) REFERENCES stock_locations(id),
    CONSTRAINT fk_stock_pickings_to_location
        FOREIGN KEY (to_location_id) REFERENCES stock_locations(id)
);


-- 一個 SKU 的一段移動。**庫存數量的真相。**
--
-- 兩端都必須有。少了目的地，這一列就退回舊模型的處境——記了鎖住多少，沒記要去哪，而那正是
-- 出貨時要補、補了就得重新詮釋既有資料的那一半。
--
-- **order_line_id 可空。** 第三個 change 的入庫 move 背後沒有任何訂單行。現在就可空，比屆時
-- 放寬一個 NOT NULL 乾淨——放寬要同時處理既有列。
--
-- **刻意沒有 previous_move_id。** 它看起來便宜，但會把線性假設鎖進 schema：一筆補貨支撐多個
-- 下游、多來源匯入一個下游、拆分與部分完成，任何一個出現就得把所有既有的鏈重建。串接真的
-- 出現時建一張方向明確的關聯表。
--
-- 此外沒有：procure_method（沒有 MTO）、rule_id / route_ids（不做規則引擎）、priority（FIFO
-- 是唯一的排序）、restrict_partner_id（在 Odoo 19 是死欄位，預留路徑從未讀它；貨主的隔離在
-- 庫存列的鍵上）、price_unit / value（**3PL 不擁有貨，永遠不對它持有的東西估值**）、
-- picking_type_id（Odoo 重複存是為了規則查找，我們不做規則，重複只會多一份要對齊的真相）。
CREATE TABLE stock_moves (
    id UUID PRIMARY KEY,
    picking_id UUID,
    owner_id UUID NOT NULL,
    sku_code VARCHAR(64) NOT NULL,
    from_location_id UUID NOT NULL,
    to_location_id UUID NOT NULL,
    -- 這一段是為哪一條需求做的。入庫時為空。
    --
    -- 這是需求與執行之間**唯一**的連結。對應 Odoo 的 stock_move.sale_line_id。
    order_line_id UUID,
    -- 需要多少。Odoo 有三個數量欄：product_uom_qty（以行的單位計的需求）、product_qty
    -- （換算成商品基準單位的需求）、quantity（實際動了多少）。前兩個的差別來自單位換算，
    -- 而本系統沒有 UoM；第三個屬 R7。因此只有一欄。
    demand_quantity INTEGER NOT NULL,
    state VARCHAR(32) NOT NULL,
    -- 這一段是什麼時候被建立的。
    --
    -- **「還在等貨」變成一列真實資料，價值有一半在這裡。** 少了時間戳，看得到「有一列」卻
    -- 看不出「它躺了一個月」，而後者才是診斷要的東西。UUID v7 的 id 確實編了時間，但那是
    -- 隱晦的依賴，而且回答不了下面那個問題。
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 什麼時候配到貨的。還在等貨時為空。
    --
    -- 與 created_at 分開存，因為兩者回答不同的問題：一個是「等了多久」（診斷），一個是
    -- 「什麼時候鎖住的」（對帳）。合成一欄就分不出來。這與 orders 把 received_at 和
    -- placed_at 分開存是同一個判準。
    assigned_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT fk_stock_moves_picking FOREIGN KEY (picking_id) REFERENCES stock_pickings(id),
    CONSTRAINT fk_stock_moves_sku
        FOREIGN KEY (owner_id, sku_code) REFERENCES skus(owner_id, sku_code),
    CONSTRAINT fk_stock_moves_from_location
        FOREIGN KEY (from_location_id) REFERENCES stock_locations(id),
    CONSTRAINT fk_stock_moves_to_location
        FOREIGN KEY (to_location_id) REFERENCES stock_locations(id),
    CONSTRAINT fk_stock_moves_order_line
        FOREIGN KEY (order_line_id) REFERENCES order_lines(id),
    CONSTRAINT ck_stock_moves_demand_quantity_positive CHECK (demand_quantity > 0),

    -- CONFIRMED  要貨，還沒拿到
    -- ASSIGNED   貨已鎖定
    -- DONE       貨真的動了
    -- CANCELLED  這一段不做了
    --
    -- **DONE 現在沒有任何產生者，但必須在值域裡。** demand_lines 的謂詞是「這條行有沒有
    -- move」，而已完成的 move 也算有——少了它，R7 每一張已出貨的單都會重新變成待接手的需求，
    -- 而**那一刻不會有任何測試失敗**。與 stock_reservations.CONSUMED 當初的判斷相同。
    --
    -- **沒有 WAITING（等上一段）**：沒有上一段。它與依賴關係表是同一件事的兩半，一起到來。
    -- **沒有 PARTIALLY_AVAILABLE**：ship-complete 下整批配到或整批不配，部分可用不是一個
    -- 會停留的狀態。
    CONSTRAINT ck_stock_moves_state
        CHECK (state IN ('CONFIRMED', 'ASSIGNED', 'DONE', 'CANCELLED')),
    -- 狀態與時間戳要對得上：還在等貨就不該有配到的時刻，配到了就該有。
    -- 這與 orders 的 ck_orders_status_timestamps 是同一個手法——一個沒有被約束綁住的
    -- 時間戳遲早會出現「狀態說配到了，時刻卻是空的」這種對不起來的列。
    CONSTRAINT ck_stock_moves_assigned_at CHECK (
        (state = 'CONFIRMED' AND assigned_at IS NULL)
     OR (state <> 'CONFIRMED' AND (state = 'CANCELLED' OR assigned_at IS NOT NULL))
    )
);

-- 待配佇列：這個貨主在這個位置、這個 SKU 上還在等貨的 move，依到達順序。
--
-- 等值篩選在前、排序鍵其次，與 idx_stock_pools_fefo 同一個手法。排序鍵用 order_line_id
-- （UUID v7，等於到達順序），與舊佇列查詢一致。
CREATE INDEX idx_stock_moves_waiting
    ON stock_moves (owner_id, from_location_id, sku_code, order_line_id)
    WHERE state = 'CONFIRMED';

-- 「這條需求行被接手了嗎」——demand_lines 的謂詞用它。
CREATE INDEX idx_stock_moves_order_line ON stock_moves (order_line_id);


-- 那一段實際從哪一批取用。
--
-- 粒度是「move × 批」：一段搬運跨三批就是三列。曾考慮「一列內含批次清單」，否決的理由是
-- 釋放與消耗都逐批發生（出貨時某一批先被揀完），一列多批表達不了部分消耗。
--
-- **外鍵直指庫存列。** Odoo 的 move line 不指 quant，靠 (商品, 位置, 批號, 包裝, 貨主) 隱式
-- 配對——那個做法的前提是有批號表，而本系統的批次身分在 (入庫日, 效期) 裡，必須指名是哪一批。
--
-- **刻意沒有自己的狀態。** 它的狀態就是所屬 move 的狀態：move ASSIGNED 且本列存在 = 已預留、
-- move DONE 且本列存在 = 已出庫扣帳。而「已釋放」不是一個狀態——**釋放是刪除這一列**。
--
-- Odoo 的 stock_move_line **有** state 欄位，但它是 related='move_id.state' 的 stored 副本，
-- 不是獨立的生命週期——它物化那一份只為了畫面篩選。我們不物化，所以沒有兩份要對齊。
-- 寫下來是因為拿 Odoo DDL 來對照的人會以為這裡漏了一欄。
--
-- **也沒有時間戳。** 一條明細的時間就是它所屬 move 被配到的時刻（stock_moves.assigned_at），
-- 而明細只在那一刻整批建立。多存一份只會多一組要對齊的真相。
--
-- 一條被釋放的預留不表達任何事實：貨沒有動，也沒有被鎖住。留著它等於讓每個讀取端都要記得
-- 過濾，而舊 demand_lines 的 status IN ('ACTIVE','CONSUMED') 正是那個負擔的具體形式。
--
-- 代價要寫明：**釋放的歷史因此不留在這裡**。它留在 move 的狀態轉換上，而搬運的歷史本來就
-- 該記在搬運上。
CREATE TABLE stock_move_lines (
    id UUID PRIMARY KEY,
    move_id UUID NOT NULL,
    stock_pool_id UUID NOT NULL,
    quantity INTEGER NOT NULL,

    -- 同一段搬運對同一批只能有一列；跨批則是多列。
    CONSTRAINT uq_stock_move_lines_move_pool UNIQUE (move_id, stock_pool_id),
    CONSTRAINT fk_stock_move_lines_move FOREIGN KEY (move_id) REFERENCES stock_moves(id),
    CONSTRAINT fk_stock_move_lines_stock_pool
        FOREIGN KEY (stock_pool_id) REFERENCES stock_pools(id),
    CONSTRAINT ck_stock_move_lines_quantity_positive CHECK (quantity > 0)
);

-- 「這一批被哪些搬運取用」——對帳與診斷的入口。反方向由上面的 unique 覆蓋。
CREATE INDEX idx_stock_move_lines_stock_pool ON stock_move_lines (stock_pool_id);
