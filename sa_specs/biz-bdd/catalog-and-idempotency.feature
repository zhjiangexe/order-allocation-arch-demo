# 對應：e2e/spec/features/catalog-and-idempotency.feature
@events @business-flow
Feature: 下單前主檔與同步命令的邊界行為
  下單端能取得履約所需主檔，並以業務識別碼防止重複下單或重複收貨。

  # case-id: CAT-001
  Scenario: 下單端可以依貨主逐層取得商品、規格、可用倉與庫位
    Given 已建立貨主「甲貨主」及其商品「測試茶品」
    And 該商品具有可下單的 SKU，且溫層為常溫
    And 甲貨主可使用「北部倉」，倉內具有「北部倉庫位」
    When 下單端查詢貨主清單
    Then 清單包含「甲貨主」
    When 下單端依甲貨主查詢商品，再依商品查詢 SKU
    Then 能取得該貨主的測試茶品、常溫資訊及所屬 SKU
    When 下單端查詢甲貨主的可用倉，再查詢北部倉的庫位
    Then 能取得「北部倉」及「北部倉庫位」

  # case-id: CAT-002
  Scenario: 相同貨主與上游單號不能建立第二張實體訂單
    Given 甲貨主準備了一張具有唯一上游單號、需求為 1 件的訂單
    When 下單端首次提交該訂單
    Then 系統接受訂單並回傳訂單識別碼
    When 下單端以相同貨主、相同上游單號及完全相同內容再次提交
    Then 系統以資料衝突拒絕建立第二張訂單

  # case-id: CAT-003
  Scenario: 收貨以 receiptId 保證重試不會重複入帳，內容不同則拒絕
    Given 指定貨主與倉庫的該 SKU 尚無庫存
    And 一筆具有唯一 receiptId 的收貨請求要將 5 件有效庫存收入指定庫位
    When 收貨端首次提交該收貨請求
    Then 系統確認該 receiptId、SKU 與收貨數量 5 件
    When 收貨端以相同 receiptId 及完全相同內容重送
    Then 系統仍接受該請求
    And 該收貨批次的在手量為 5 件、預留量為 0 件
    When 收貨端保留原 receiptId，將數量改為 6 件後提交
    Then 系統以收貨請求內容衝突拒絕該請求
