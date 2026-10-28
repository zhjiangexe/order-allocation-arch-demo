# 對應：e2e/spec/features/temporal-fulfillment.feature
@temporal @business-flow
Feature: Temporal 模式的配貨與履約流程
  Workflow 協調履約並保留處理結果，缺貨等待與庫存隔離仍遵守相同業務規則。

  Background:
    Given 系統以 Temporal 模式協調履約

  # case-id: TMP-FUL-001
  Scenario: Temporal workflow 會完成訂單履約並保留終態
    Given 該 SKU 有 20 件在手庫存、0 件預留及 20 件可承諾量
    When 貨主提交需求為 4 件的訂單
    Then 系統接受訂單並回傳訂單識別碼
    And 訂單最終履約完成
    And 庫存作業來源對應該訂單，作業與 4 件移動需求均已完成
    And 該移動需求具有一筆 4 件的批次配置
    And 恰有一張 Shipment 完成交運，並保留波次識別碼與一行 4 件出貨明細
    And 可查詢該訂單的 workflow，流程已結束、配貨已提交且結果為履約完成
    And Workflow 記錄的 Shipment 與實際交運的 Shipment 相同
    And 該 SKU 的庫存餘額為
      | 在手量 | 預留量 | 可承諾量 |
      | 16     | 0      | 16       |

  # case-id: TMP-FUL-002
  Scenario: Temporal workflow 在缺貨期間保持等待，補貨後從原 workflow 繼續完成
    Given 該 SKU 尚無可用庫存
    When 貨主提交需求為 3 件的訂單
    Then 庫存作業已確認，移動需求尚無批次配置且尚未建立 Shipment
    And 可查詢該訂單的 workflow，流程停在配貨階段、配貨已請求且尚無終態結果
    When 倉庫收進該 SKU 的 3 件有效庫存
    Then 不需重送訂單，該訂單最終履約完成
    And 庫存作業與移動需求均已完成，並具有一筆批次配置
    And 該訂單的 workflow 仍可查詢，流程已結束且結果為履約完成

  # case-id: TMP-FUL-003
  Scenario: Temporal 已完成交運時同樣拒絕取消
    Given 一張需求為 2 件的訂單已履約完成，workflow 結果為履約完成
    When 客戶提交取消請求
    Then 系統以 workflow 已無法接受取消請求的衝突拒絕該請求
    And 訂單仍為履約完成
    And Workflow 結果仍為履約完成

  # 展開來源的 support/verify-stock-isolation.feature。
  # case-id: TMP-FUL-004
  Scenario: 相同 SKU 在同一倉庫的其他貨主有貨或補貨，不得供應目標貨主
    Given 同一倉庫中，目標貨主的該 SKU 有 2 件可用庫存，另一貨主有 7 件
    When 目標貨主提交需求為 4 件的訂單
    Then 目標訂單待配貨、尚無批次配置且尚未建立 Shipment
    When 另一貨主提交需求為 9 件的控制訂單
    Then 控制訂單也待配貨，兩個貨主的庫存均未被預留
    When 倉庫替另一貨主補入 5 件該 SKU
    Then 控制訂單最終以另一貨主的庫存完成 9 件交運，workflow 結果為履約完成
    And 另一貨主剩餘 3 件可用庫存且沒有預留
    And 目標訂單仍待配貨，原有 2 件庫存沒有被預留且沒有 Shipment
    When 倉庫只替目標貨主補入 2 件該 SKU
    Then 目標訂單最終以自己的庫存完成 4 件交運，workflow 結果為履約完成
    And 目標貨主的在手量與預留量均為 0 件
    And 另一貨主仍保有 3 件可用庫存且沒有預留

  # 展開來源的 support/verify-stock-isolation.feature。
  # case-id: TMP-FUL-005
  Scenario: 相同貨主與 SKU 在其他倉庫有貨或補貨，不得供應目標倉庫
    Given 同一貨主的該 SKU 在目標倉庫有 2 件可用庫存，在另一倉庫有 7 件
    When 貨主提交由目標倉庫履約、需求為 4 件的訂單
    Then 目標訂單待配貨、尚無批次配置且尚未建立 Shipment
    When 貨主提交由另一倉庫履約、需求為 9 件的控制訂單
    Then 控制訂單也待配貨，兩個倉庫的庫存均未被預留
    When 另一倉庫收進該 SKU 的 5 件有效庫存
    Then 控制訂單最終以另一倉庫的庫存完成 9 件交運，workflow 結果為履約完成
    And 另一倉庫剩餘 3 件可用庫存且沒有預留
    And 目標訂單仍待配貨，目標倉庫原有 2 件庫存沒有被預留且沒有 Shipment
    When 目標倉庫收進該 SKU 的 2 件有效庫存
    Then 目標訂單最終以目標倉庫的庫存完成 4 件交運，workflow 結果為履約完成
    And 目標倉庫該 SKU 的在手量與預留量均為 0 件
    And 另一倉庫仍保有 3 件可用庫存且沒有預留
