-- 同步收貨 request 的業務冪等邊界。receipt_id 由呼叫方產生並在 HTTP retry 時重用。
--
-- 不只存一個 generic key：同一 receipt_id 若被拿來送不同內容，必須明確拒絕，不能把第二個
-- request 靜默當成第一次的 replay。這些欄位因此同時是 request fingerprint。
--
-- 本表不建立外鍵。它記錄的是 caller request identity，不是庫存主檔；實際 owner／location／SKU
-- 驗證與庫存異動仍由同一 transaction 裡的收貨 use case 負責。use case 失敗時這列一起 rollback。
CREATE TABLE stock_receipt_requests (
    receipt_id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    facility_id UUID NOT NULL,
    location_id UUID NOT NULL,
    sku_code VARCHAR(64) NOT NULL,
    in_date DATE NOT NULL,
    expiry_date DATE NOT NULL,
    quantity INTEGER NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT ck_stock_receipt_requests_quantity_positive CHECK (quantity > 0)
);
