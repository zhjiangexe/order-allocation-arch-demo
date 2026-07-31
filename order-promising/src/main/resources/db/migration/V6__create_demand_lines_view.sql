-- 待配需求。ordering 側發布，allocation 唯讀消費並映射成它自己的 Demand / DemandLine。
--
-- 這是兩個 context 之間的介面。allocation 因此不 import ordering 的 Order，也不出現
-- orders／order_lines 這兩個表名——它查的是這個 view，架構測試的「不得出現該表名」不需要
-- 為讀取開例外。
--
-- 為什麼是 view 而不是投影表：投影表要自己維護、有延遲、要對帳，而換到的唯一好處是「已
-- 滿足」變成自己的欄位、不依賴他人的 enum。把那個謂詞放進 view 定義之後，好處的大部分被
-- 抵銷——謂詞仍要隨 ReservationStatus 演進更新，但只有一個地方要改。
--
-- 為什麼是 view 而不是 Port 介面：多一層介面，換到的東西 view 也給。
CREATE VIEW demand_lines AS
SELECT ol.order_id,
       ol.id       AS order_line_id,
       ol.owner_id,
       o.fulfillment_node_id AS node_id,
       ol.sku_code,
       ol.quantity,
       o.received_at
  FROM order_lines ol
  JOIN orders o ON o.id = ol.order_id
 -- 取消由 ordering 發起並同步寫入，所以這個判準即時正確。
 WHERE o.cancelled_at IS NULL
   -- 「還欠什麼」由 allocation 自己的資料決定，不看 ordering 的配貨狀態。
   --
   -- **刻意不含 ol.status。** 寫入是非同步的（allocation 發事實 → ordering 收到後才改
   -- Order），所以 ordering 的配貨狀態落後於 allocation 的決策：
   --
   --   1. allocation 讀到需求、配貨成功、發出事件
   --   2. ordering 還沒處理該事件 → order_lines.status 仍是 BACKORDERED
   --   3. 另一筆補貨進來、又讀到同一筆需求 → 重複預留
   --
   -- 讀不到那個欄位比讀得到而約定不用更強：後者會被一次「順手加上 status 過濾」的修改推翻。
   AND NOT EXISTS (
     SELECT 1
       FROM stock_reservations sr
      WHERE sr.order_line_id = ol.id
        -- ACTIVE：配到、尚未出貨。CONSUMED：出貨後扣帳（R3 加了型別，R7 才開始產生）。
        --
        -- **CONSUMED 現在不會出現，但謂詞必須現在就寫對。** 少了它，R7 每一張已出貨的訂單
        -- 都會重新變成待配需求而被配第二次——而那一刻不會有任何測試失敗，因為出貨流程還不
        -- 存在。RELEASED（取消時釋放）不算已滿足，該行要重新回到佇列。
        --
        -- 日後再擴充 ReservationStatus 必須回頭檢查這一行。
        AND sr.status IN ('ACTIVE', 'CONSUMED'));
