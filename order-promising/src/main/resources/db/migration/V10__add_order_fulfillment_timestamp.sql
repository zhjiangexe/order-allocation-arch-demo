-- Order 的終態與 WMS Shipment 的離倉事實是不同概念。fulfillment coordinator 只在出庫
-- movements 完成後填入此欄；既有資料尚未履約，因此允許 NULL。
ALTER TABLE orders
    ADD COLUMN fulfilled_at TIMESTAMPTZ;
