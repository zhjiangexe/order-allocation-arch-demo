# 對應：e2e/spec/features/events-cancellation.feature
@events @cancellation @business-flow
Feature: Events 模式的取消流程
  可逆的訂單可以取消並退出庫存競爭；完成交運的訂單不能透過取消回復。

  Background:
    Given 系統以 Events 模式協調履約

  # case-id: EVT-CAN-001
  Scenario: 待配貨訂單取消後不會再被補貨喚醒，庫存會留給下一張有效訂單
    Given 一張需求為 5 件的訂單因缺貨而等待配貨
    And 庫存作業已確認，尚無批次配置及 Shipment
    When 客戶提交具有唯一 requestId、請求時間及原因的取消請求
    Then 系統受理該取消請求並回傳相同 requestId
    And 訂單、庫存作業與移動需求最終均已取消
    And 沒有批次配置及 Shipment
    And 取消流程最終完成，倉庫結果為沒有 Shipment，並保留訂單端處理結果
    When 查詢者以另一個訂單識別碼查詢該取消請求
    Then 系統回報找不到該取消流程
    When 客戶重送完全相同的取消請求
    Then 系統回報該請求已提出，並回傳原 requestId
    When 貨主提交相同 SKU、需求為 5 件的下一張有效訂單
    Then 下一張訂單進入缺貨等待，尚無批次配置
    When 倉庫收進該 SKU 的 5 件有效庫存
    Then 下一張有效訂單最終履約完成
    And 原訂單仍維持已取消

  # case-id: EVT-CAN-002
  Scenario: 已交運完成的訂單拒絕取消，改由退貨流程處理
    # 此案例只驗證取消被拒絕，未執行退貨流程。
    Given 一張需求為 2 件的訂單已履約完成，Shipment 已交付承運商
    When 客戶提交取消請求
    Then 系統先受理取消協調並回傳該 requestId
    And 取消流程最終被拒絕，倉庫結果為拒絕，沒有訂單端取消結果
    And 訂單仍為履約完成
    And Shipment 仍為已交付承運商
