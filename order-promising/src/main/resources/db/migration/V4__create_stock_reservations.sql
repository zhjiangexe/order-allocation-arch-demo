-- 預留：某一條訂單行從某一批貨鎖下的額度。
--
-- 粒度是「訂單行 × 批次」而不是「訂單」——一條行的需求跨三批就是三筆預留。
--
-- 曾考慮「一條行一筆預留，內含批次清單」，否決的理由是釋放與消耗都逐批發生（出貨時某一批
-- 先被揀完），一筆多批的預留表達不了部分消耗，而那正是 R7 兩本帳要對的東西。

CREATE TABLE stock_reservations (
    id UUID PRIMARY KEY,
    -- 指向行而非訂單。訂單層級的參照在多行放寬後會失去意義，而 R8 之前它也已經
    -- 表達不了「這一批是為哪一條行鎖的」。
    -- 這筆預留是為哪一張單鎖的。
    --
    -- 值可以從 order_line_id join order_lines 得到，但那條路徑要求 allocation 認識 ordering
    -- 的表——而取消時「釋放這張單的全部預留」是 allocation 自己的動作，它不該為此去問別人。
    -- 值不可變（一筆預留屬於哪張單不會改），所以沒有同步成本。
    --
    -- 刻意不建外鍵指向 orders：那會讓 allocation 的 schema 依賴 ordering 的表。完整性由
    -- order_line_id 的外鍵保證——行存在就蘊含它的訂單存在。
    order_id UUID NOT NULL,
    order_line_id UUID NOT NULL,
    stock_pool_id UUID NOT NULL,
    quantity INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    reserved_at TIMESTAMPTZ NOT NULL,
    released_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,

    -- 同一條行對同一批只能有一筆預留；跨批則是多筆。原本的 UNIQUE (order_id) 表達的是
    -- 「一張單一筆預留」，分批之後那條規則不再成立。
    CONSTRAINT uq_stock_reservations_line_pool UNIQUE (order_line_id, stock_pool_id),
    CONSTRAINT fk_stock_reservations_order_line
        FOREIGN KEY (order_line_id) REFERENCES order_lines(id),
    CONSTRAINT fk_stock_reservations_stock_pool
        FOREIGN KEY (stock_pool_id) REFERENCES stock_pools(id),
    CONSTRAINT ck_stock_reservations_quantity_positive CHECK (quantity > 0),

    -- CONSUMED 於出貨時扣掉在手量後套用。**本階段不產生這個狀態**——它為 R7 的兩本帳
    -- 準備，但必須現在就存在，因為 R4 的 demand_lines view 會用 status IN ('ACTIVE',
    -- 'CONSUMED') 當「已滿足」的謂詞，而 R4 緊接在後。
    --
    -- 日後再擴充這個 enum 時必須同步檢查那個 view：漏掉會讓已出貨的訂單重新出現在待配
    -- 佇列，而當下沒有任何測試會發現。
    CONSTRAINT ck_stock_reservations_status
        CHECK (status IN ('ACTIVE', 'RELEASED', 'CONSUMED')),
    CONSTRAINT ck_stock_reservations_released_state CHECK (
        (status IN ('ACTIVE', 'CONSUMED') AND released_at IS NULL)
        OR (status = 'RELEASED' AND released_at IS NOT NULL AND released_at >= reserved_at)
    )
);

-- 「這條行預留了哪些批」與「這批被哪些行預留」都要查得快。前者是釋放與出貨的入口，
-- 後者是對帳與診斷的入口。
-- 取消時以訂單釋放全部預留：一條行跨三批就有三筆，全部都要放。
CREATE INDEX idx_stock_reservations_order ON stock_reservations (order_id);
CREATE INDEX idx_stock_reservations_line ON stock_reservations (order_line_id);
CREATE INDEX idx_stock_reservations_pool ON stock_reservations (stock_pool_id);
