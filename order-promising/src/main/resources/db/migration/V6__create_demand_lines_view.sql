-- 待配需求。ordering 側發布，allocation 唯讀消費並映射成它自己的 Demand / DemandLine。
--
-- 這是兩個 context 之間的介面。allocation 因此不 import ordering 的 Order，也不出現
-- orders／order_lines 這兩個表名——架構測試的「不得出現該表名」不需要為讀取開例外。
--
-- 為什麼是 view 而不是投影表：投影表要自己維護、有延遲、要對帳，而換到的唯一好處是「已
-- 滿足」變成自己的欄位、不依賴他人的 enum。把那個謂詞放進 view 定義之後，好處的大部分被
-- 抵銷——謂詞仍要隨 ReservationStatus 演進更新，但只有一個地方要改。
--
-- 為什麼是 view 而不是 Port 介面：多一層介面，換到的東西 view 也給。
--
-- **倉→位置的解析放在這裡。** 訂單說倉（下單只決定倉，位置屬於執行層），配貨說位置。轉換
-- 放在兩者交會處，兩邊各自只需要一套詞彙；放進 repository 則會讓 allocation 同時認識倉與
-- 位置，而它只需要後者。
CREATE VIEW demand_lines AS
SELECT ol.order_id,
       ol.id       AS order_line_id,
       ol.owner_id,
       sl.id       AS location_id,
       ol.sku_code,
       ol.quantity,
       o.received_at
  FROM order_lines ol
  JOIN orders o ON o.id = ol.order_id
  -- INNER JOIN 是刻意的：倉沒有內部位置時，那張單的需求不會出現在這裡。
  --
  -- 那是一個目前寫不出來的狀態（位置沒有寫入介面，種子保證每個倉都有一個），但若它發生，
  -- 「不出現」與「出現卻永遠配不到」的結果相同——貨不可能存在於一個不存在的位置。選前者是
  -- 因為 LEFT JOIN 會讓 location_id 為 NULL 的列流進配貨，而每個下游都得處理那個不會發生
  -- 的狀態。
  JOIN stock_locations sl
    ON sl.warehouse_id = o.fulfillment_node_id
   AND sl.usage = 'INTERNAL'
 -- 取消由 ordering 發起並同步寫入，所以這個判準即時正確。
 WHERE o.cancelled_at IS NULL
   -- 「這條行被執行層接手了嗎」——由 move 的存在回答。
   --
   -- **這個 view 的問題換了。** 它原本問「還欠什麼」，判準是「沒有有效預留」。有了搬運之後
   -- 那個問題有了更好的家：一段還在等貨的 move 自己就說得出來。這裡問的因此變窄——**哪些行
   -- 執行層還沒接手**——而它存在的理由是執行層沒有別的辦法知道：宣告新訂單的事件只帶識別碼
   -- （EventSeparationTest 釘死了「事件是通知，不是狀態傳輸」），而反方向讓 ordering 直接寫
   -- stock_moves 是邊界的反面。
   --
   -- 於是有兩個佇列：這個 view 回答「還沒被接手」，stock_moves.state = 'CONFIRMED' 回答
   -- 「還在等貨」。
   --
   -- **不看 state——任何狀態的 move 都算已接手。** 尤其 DONE：它現在沒有產生者，但少了這個
   -- 涵蓋，R7 每一張已出貨的單都會重新變成待接手的需求而被接手第二次，**而那一刻不會有任何
   -- 測試失敗**，因為出貨流程還不存在。這與原本 CONSUMED 的判斷完全相同。
   --
   -- （原本這裡有一段解釋「刻意不含 ol.status，因為 ordering 的配貨狀態非同步落後於
   -- allocation 的決策」。**那個理由消失了**：新謂詞讀的是執行層自己寫的表，沒有時間差。
   -- 保留不看 ol.status 的做法，但它現在只是「不需要」而不是「不能」。）
   AND NOT EXISTS (
     SELECT 1
       FROM stock_moves m
      WHERE m.order_line_id = ol.id);
