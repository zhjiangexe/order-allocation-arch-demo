# 對應：e2e/spec/features/events-shipment-cancellation.feature
@events @cancellation @business-flow
Feature: Events 模式在倉內作業前取消 Shipment
  取消尚未作業的 Shipment 後，原預留能重新供應等待中的訂單。

  Background:
    Given 系統以 Events 模式協調履約
    And Shipment 建立後暫緩倉內作業，保留可取消窗口

  # case-id: EVT-SHIP-CAN-001
  Scenario: Shipment 作業前取消會釋放預留，等待訂單不需補貨即可完成
    Given 該 SKU 有 10 件在手庫存、0 件預留及 10 件可承諾量
    When 貨主提交需求為 3 件的第一張訂單
    Then 第一張訂單完成配貨，庫存作業與移動需求均已指派
    And 已建立一張尚未作業的 Shipment，並有一筆批次配置
    And 庫存餘額為在手 10 件、預留 3 件及可承諾 7 件
    When 貨主提交相同 SKU、需求為 8 件的第二張訂單
    Then 第二張訂單待配貨，8 件移動需求已確認但沒有批次配置或 Shipment
    And 庫存仍為在手 10 件、預留 3 件及可承諾 7 件
    When 客戶在倉內作業開始前提交第一張訂單的取消請求
    Then 系統受理取消請求
    And 第一張訂單、庫存作業、移動需求與 Shipment 最終均已取消
    And 原移動需求的批次配置已清空
    And Shipment 保留原取消請求的識別碼、時間、原因及實際取消時間
    And 取消流程已完成，倉庫結果為 Shipment 已取消，並保留訂單端處理結果
    When 系統繼續處理等待訂單，期間不再收貨也不重送訂單
    Then 第二張訂單自行完成配貨並建立一張尚未作業的 Shipment
    And 第二張訂單以一筆批次配置預留 8 件
    And 庫存餘額為在手 10 件、預留 8 件及可承諾 2 件
    When 第二張訂單的倉內作業與交運完成
    Then 第二張訂單履約完成，庫存作業與 8 件移動需求均已完成
    And 第二張訂單仍只有原本那張 Shipment，出貨明細為 8 件
    And 庫存餘額為在手 2 件、預留 0 件及可承諾 2 件
    And 第一張訂單、庫存作業及移動需求仍已取消，沒有批次配置
    And 第一張訂單仍只有一張已取消的 Shipment
