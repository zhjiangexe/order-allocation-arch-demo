-- 舊版把初次配貨缺貨誤存成獨立狀態；現在由 PENDING + StockMove 等待佇列表達。
-- 未來若支援部分履行，應建立剩餘 FulfillmentDemand，而不是重新加入訂單狀態。
UPDATE orders
SET status = 'PENDING'
WHERE status = 'BACKORDERED' OR status = 'WAITING_FOR_SUPPLY';

-- 等待供應時間不再是 Order projection 的狀態；等待起點改由 StockMove 的建立時間追蹤。
ALTER TABLE orders DROP COLUMN backordered_since;
