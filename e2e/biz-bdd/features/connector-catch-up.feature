# 對應：e2e/spec/features/connector-catch-up.feature
@events @messaging-resilience
Feature: Debezium connector 暫停後追趕
  事件傳遞暫停時仍能接受訂單，恢復後能繼續完成先前已接受的履約需求。

  # case-id: MSG-001
  Scenario: Connect 暫停期間已提交的訂單，恢復後仍會從 WAL 追上並完成
    Given 系統以 Events 模式協調履約，該 SKU 有足夠庫存
    And Debezium connector 已確認暫停傳遞事件
    When 貨主提交需求為 2 件的訂單
    Then 系統接受訂單、回傳訂單識別碼，訂單狀態為待配貨
    And 在事件傳遞暫停的觀察期間，訂單仍待配貨
    When 恢復 Debezium connector，且 connector 與傳遞任務已恢復運作
    Then 不需重送訂單，先前已提交的訂單最終履約完成
    And 已完成的庫存作業來源對應該訂單，移動需求亦已完成
    And 恰有一張 Shipment 已交付承運商
    And 履約查詢沒有 Temporal workflow
