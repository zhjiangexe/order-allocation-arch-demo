# 對應：e2e/spec/features/events-fulfillment.feature
@events @business-flow
Feature: Events 模式的配貨與履約流程
  訂單由事件驅動配貨、倉內作業與交運；缺貨需求等待補貨，並遵守整單配貨與庫存隔離。

  Background:
    Given 系統以 Events 模式協調履約

  # case-id: EVT-FUL-001
  Scenario: 有足夠庫存時，訂單會走完配貨、倉內作業與交運
    Given 該 SKU 有 20 件在手庫存、0 件預留及 20 件可承諾量
    When 貨主提交需求為 3 件的訂單
    Then 系統接受訂單並回傳訂單識別碼
    And 訂單最終履約完成
    And 庫存作業來源對應該訂單，作業與其 3 件移動需求均已完成
    And 該移動需求具有一筆 3 件的批次配置
    And 恰有一張 Shipment 完成交運，並保留波次識別碼與 3 件出貨明細
    And 履約查詢顯示不適用 Temporal workflow
    And 該 SKU 的庫存餘額為
      | 在手量 | 預留量 | 可承諾量 |
      | 17     | 0      | 17       |

  # case-id: EVT-FUL-002
  Scenario: 缺貨訂單會等待，收貨後由 availability event 喚醒並完成
    Given 該 SKU 尚無可用庫存
    When 貨主提交需求為 4 件的訂單
    Then 訂單保持待配貨
    And 庫存作業與 4 件移動需求為已確認，尚無批次配置
    And 尚未建立 Shipment
    When 倉庫收進該 SKU 的 4 件有效庫存
    Then 不需重送訂單，訂單最終履約完成
    And 庫存作業與移動需求均已完成，並具有一筆批次配置
    And Shipment 已交付承運商

  # case-id: EVT-FUL-003
  Scenario: ship-complete 會等所有 SKU 都足夠，不會先占用充足的那一行
    Given SKU A 有 10 件可用庫存，SKU B 尚無可用庫存
    When 貨主提交包含下列明細的訂單
      | SKU | 需求量 |
      | A   | 5      |
      | B   | 4      |
    Then 整組庫存作業保持已確認
    And A 的 5 件需求與 B 的 4 件需求均尚無批次配置
    And 尚未建立 Shipment
    When 倉庫只補入 SKU B 的 4 件有效庫存
    Then 不需重送訂單，原訂單最終履約完成
    And 訂單保留兩行明細
    And 庫存作業與兩個移動需求均已完成
    And Shipment 包含兩行出貨明細

  # case-id: EVT-FUL-004
  Scenario: 同一 SKU 出現在兩行時會先加總需求，再一次判斷整張訂單
    Given 該 SKU 有 10 件可用庫存
    When 貨主提交同一 SKU 的兩行訂單明細，數量分別為 2 件及 3 件
    Then 訂單最終履約完成
    And 庫存作業保留該 SKU 的兩個已完成移動需求，數量分別為 2 件及 3 件
    And Shipment 保留兩行出貨明細，數量分別為 2 件及 3 件

  # case-id: EVT-FUL-005
  Scenario: FIFO 的隊首不足時不能跳過它配置後來的小單
    Given 該 SKU 尚無可用庫存
    And 兩張訂單的預定出貨時間相同
    When 貨主先提交需求為 5 件的第一張訂單，待其進入缺貨等待後再提交需求為 2 件的第二張訂單
    Then 兩張訂單的庫存作業均為已確認且尚無批次配置
    When 倉庫先補入 2 件有效庫存
    Then 第一張與第二張訂單都仍待配貨
    When 倉庫再補入 3 件有效庫存
    Then 第一張訂單最終履約完成
    And 第二張訂單仍待配貨
    When 倉庫再補入 2 件有效庫存
    Then 第二張訂單最終履約完成

  # 展開來源的 support/verify-stock-isolation.feature。
  # case-id: EVT-FUL-006
  Scenario: 相同 SKU 在同一倉庫的其他貨主有貨或補貨，不得供應目標貨主
    Given 同一倉庫中，目標貨主的該 SKU 有 2 件可用庫存，另一貨主有 7 件
    When 目標貨主提交需求為 4 件的訂單
    Then 目標訂單待配貨、尚無批次配置且尚未建立 Shipment
    When 另一貨主提交需求為 9 件的控制訂單
    Then 控制訂單也待配貨，兩個貨主的庫存均未被預留
    When 倉庫替另一貨主補入 5 件該 SKU
    Then 控制訂單最終以另一貨主的庫存完成 9 件交運
    And 另一貨主剩餘 3 件可用庫存且沒有預留
    And 目標訂單仍待配貨，原有 2 件庫存沒有被預留且沒有 Shipment
    When 倉庫只替目標貨主補入 2 件該 SKU
    Then 目標訂單最終以自己的庫存完成 4 件交運
    And 目標貨主的在手量與預留量均為 0 件
    And 另一貨主仍保有 3 件可用庫存且沒有預留

  # 展開來源的 support/verify-stock-isolation.feature。
  # case-id: EVT-FUL-007
  Scenario: 相同貨主與 SKU 在其他倉庫有貨或補貨，不得供應目標倉庫
    Given 同一貨主的該 SKU 在目標倉庫有 2 件可用庫存，在另一倉庫有 7 件
    When 貨主提交由目標倉庫履約、需求為 4 件的訂單
    Then 目標訂單待配貨、尚無批次配置且尚未建立 Shipment
    When 貨主提交由另一倉庫履約、需求為 9 件的控制訂單
    Then 控制訂單也待配貨，兩個倉庫的庫存均未被預留
    When 另一倉庫收進該 SKU 的 5 件有效庫存
    Then 控制訂單最終以另一倉庫的庫存完成 9 件交運
    And 另一倉庫剩餘 3 件可用庫存且沒有預留
    And 目標訂單仍待配貨，目標倉庫原有 2 件庫存沒有被預留且沒有 Shipment
    When 目標倉庫收進該 SKU 的 2 件有效庫存
    Then 目標訂單最終以目標倉庫的庫存完成 4 件交運
    And 目標倉庫該 SKU 的在手量與預留量均為 0 件
    And 另一倉庫仍保有 3 件可用庫存且沒有預留
