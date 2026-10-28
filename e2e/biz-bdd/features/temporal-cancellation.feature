# 對應：e2e/spec/features/temporal-cancellation.feature
@temporal @cancellation @business-flow
Feature: Temporal workflow 的取消分支
  Workflow 協調無 Shipment 與已有 Shipment 的取消，並保留請求身分與最終結果。

  Background:
    Given 系統以 Temporal 模式協調履約

  # case-id: TMP-CAN-001
  Scenario: 尚未配到庫存時，workflow 不需 Shipment 就能取消訂單
    Given 一張需求為 2 件的訂單因缺貨等待配貨
    And 該訂單的 workflow 已存在，庫存作業已確認且尚無批次配置或 Shipment
    When 客戶提交具有唯一 requestId、請求時間及原因的取消請求
    Then 系統受理取消請求
    And 訂單、庫存作業與移動需求最終均已取消
    And 沒有建立 Shipment
    And Workflow 已結束，流程結果與取消狀態均為訂單已取消
    And Workflow 保留該取消請求的 requestId

  # case-id: TMP-CAN-002
  Scenario: workflow 取消 Shipment 後釋放預留，等待訂單不需補貨即可完成
    Given 該 SKU 有 10 件在手庫存、0 件預留及 10 件可承諾量
    And Shipment 建立後暫緩倉內作業，保留可取消窗口
    When 貨主提交需求為 3 件的第一張訂單
    Then 該訂單的 workflow 已存在，並已建立一張尚未作業的 Shipment
    And 庫存餘額為在手 10 件、預留 3 件及可承諾 7 件
    When 貨主提交相同 SKU、需求為 8 件的第二張訂單
    Then 第二張訂單待配貨，8 件移動需求已確認但沒有批次配置或 Shipment
    And 庫存仍為在手 10 件、預留 3 件及可承諾 7 件
    When 暫停事件傳遞，並確認 Connect 與所有傳遞任務已暫停
    And 客戶在倉內作業開始前提交第一張訂單的取消請求
    Then 系統受理該取消請求
    When 客戶保留原 requestId 與請求時間，只修改取消原因後重送
    Then 系統回報取消內容衝突，並回傳原 requestId
    When 客戶保留原 requestId 與取消原因，只修改請求時間後重送
    Then 系統回報取消內容衝突，並回傳原 requestId
    When 客戶重送完全相同的原始取消請求
    Then 系統回報該請求已提出，並回傳原 requestId
    And 第一張訂單尚未完成取消
    And Workflow 取消狀態為已請求，保留原始請求時間
    When 恢復事件傳遞
    Then 第一張訂單、庫存作業、移動需求與 Shipment 最終均已取消
    And 原移動需求的批次配置已清空
    And Shipment 保留原取消請求的識別碼、時間、原因及實際取消時間
    And 第一張訂單的 workflow 已結束，流程結果與取消狀態均為訂單已取消
    When 系統繼續處理等待訂單，期間不再收貨也不重送訂單
    Then 第二張訂單自行完成配貨並建立一張尚未作業的 Shipment
    And 第二張訂單以一筆批次配置預留 8 件
    And 庫存餘額為在手 10 件、預留 8 件及可承諾 2 件
    When 第二張訂單的倉內作業與交運完成
    Then 第二張訂單履約完成，庫存作業與 8 件移動需求均已完成
    And 第二張訂單仍只有原本那張 Shipment，出貨明細為 8 件
    And 第二張訂單的 workflow 已結束，結果為履約完成
    And 庫存餘額為在手 2 件、預留 0 件及可承諾 2 件
    And 第一張訂單、庫存作業及移動需求仍已取消，沒有批次配置
    And 第一張訂單仍只有一張已取消的 Shipment
