@temporal @business-flow
Feature: Temporal 模式的配貨與履約流程

  Background:
    * url baseUrl
    * configure retry = { count: 90, interval: 1000 }
    * def buildOrderRequest = read('support/build-order-request.js')
    * def buildStockReceiptRequest = read('support/build-stock-receipt-request.js')

  Scenario: Temporal workflow 會完成訂單履約並保留終態
    # 與 Events 成功路徑使用不同 SKU；除了業務結果，還檢查 durable workflow 自己的狀態。
    * def externalOrderNo = 'KARATE-TMP-HAPPY-' + newId()
    * def order = buildOrderRequest({ ownerId: ownerId, facilityId: facilityId, externalOrderNo: externalOrderNo, lines: [{ skuCode: 'E2E-TMP-HAPPY', quantity: 4 }] })
    Given path 'orders'
    And request order
    When method post
    Then status 200
    And match response.orderId == '#uuid'
    * def orderId = response.orderId

    # POST 只接受訂單；retry 會持續輪詢，直到業務結果與 durable workflow 一起收斂。
    Given path 'demo', 'orders', orderId, 'fulfillment'
    And retry until response.order.status == 'FULFILLED' && response.temporalWorkflow != null && response.temporalWorkflow.outcome == 'FULFILLMENT_COMPLETED'
    When method get
    Then status 200
    # 終態快照證明各 Context 都留下結果，但不假裝驗證每一個 workflow activity 的執行順序。
    And match response.temporalWorkflow != null
    And match response.order.status == 'FULFILLED'
    And match response.stockOperation.source == { type: 'ORDER', sourceId: '#(orderId)', operationUnitKey: 'PRIMARY' }
    And match response.stockOperation.operation.state == 'DONE'
    And match response.stockOperation.moves[0].state == 'DONE'
    And match response.stockOperation.moves[0].batches == '#[1]'
    And match response.shipments == '#[1]'
    And match response.shipments[0].status == 'HANDED_OVER_TO_CARRIER'
    And match response.shipments[0].waveId == '#uuid'
    And match response.orchestrationMode == 'temporal'
    And match response.workflowQueryStatus == 'AVAILABLE'
    And match response.temporalWorkflow.phase == 'FINISHED'
    And match response.temporalWorkflow.allocationState == 'COMMITTED'
    And match response.temporalWorkflow.outcome == 'FULFILLMENT_COMPLETED'
    And match response.temporalWorkflow.shipmentId == response.shipments[0].shipmentId

  Scenario: Temporal workflow 在缺貨期間保持等待，補貨後從原 workflow 繼續完成
    # 不建立第二個 workflow，也不重送下單命令；availability event 會把 stock operation assignment signal 回原流程。
    * def sku = 'E2E-TMP-WAKE'
    * def externalOrderNo = 'KARATE-TMP-WAKE-' + newId()
    * def order = buildOrderRequest({ ownerId: ownerId, facilityId: facilityId, externalOrderNo: externalOrderNo, lines: [{ skuCode: sku, quantity: 3 }] })
    Given path 'orders'
    And request order
    When method post
    Then status 200
    * def orderId = response.orderId

    # 先確認原 workflow 停在 assignment 等待點，且 stock operation 尚未保留 batch、也未建立 Shipment。
    Given path 'demo', 'orders', orderId, 'fulfillment'
    And retry until response.stockOperation != null && response.stockOperation.operation.state == 'CONFIRMED' && response.stockOperation.moves[0].batches.length == 0 && response.temporalWorkflow != null
    When method get
    Then status 200
    And match response.orchestrationMode == 'temporal'
    And match response.workflowQueryStatus == 'AVAILABLE'
    And match response.temporalWorkflow.phase == 'ALLOCATION'
    And match response.temporalWorkflow.allocationState == 'REQUESTED'
    And match response.temporalWorkflow.outcome == null
    And match response.shipments == '#[0]'

    # 收貨只改變 availability；它應 signal 原 workflow 繼續，而不是重新下單。
    * def receiptId = newId()
    * def receipt = buildStockReceiptRequest({ receiptId: receiptId, ownerId: ownerId, facilityId: facilityId, locationId: locationId, sku: sku, quantity: 3 })
    Given path 'stock-receipts'
    And request receipt
    When method post
    Then status 200

    # 等待同一條 workflow 從等待點恢復並留下完成終態。
    Given path 'demo', 'orders', orderId, 'fulfillment'
    And retry until response.order.status == 'FULFILLED' && response.temporalWorkflow.outcome == 'FULFILLMENT_COMPLETED'
    When method get
    Then status 200
    And match response.stockOperation.operation.state == 'DONE'
    And match response.stockOperation.moves[0].state == 'DONE'
    And match response.stockOperation.moves[0].batches == '#[1]'
    And match response.orchestrationMode == 'temporal'
    And match response.workflowQueryStatus == 'AVAILABLE'
    And match response.temporalWorkflow.phase == 'FINISHED'

  Scenario: Temporal 已完成交運時同樣拒絕取消
    # orchestration 技術不同，不可逆的 handover 業務規則必須相同。
    * def externalOrderNo = 'KARATE-TMP-CANCEL-DONE-' + newId()
    * def order = buildOrderRequest({ ownerId: ownerId, facilityId: facilityId, externalOrderNo: externalOrderNo, lines: [{ skuCode: 'E2E-TMP-CANCEL-DONE', quantity: 2 }] })
    Given path 'orders'
    And request order
    When method post
    Then status 200
    * def orderId = response.orderId

    # 先跨過 handover 不可逆邊界，再驗證 Temporal 模式套用相同取消規則。
    Given path 'demo', 'orders', orderId, 'fulfillment'
    And retry until response.order.status == 'FULFILLED' && response.temporalWorkflow.outcome == 'FULFILLMENT_COMPLETED'
    When method get
    Then status 200

    * def cancellation = { requestId: '#(newId())', requestedAt: '#(now())', reason: 'Temporal 交運後要求取消' }
    Given path 'orders', orderId, 'cancellation-requests'
    And request cancellation
    When method post
    Then status 409
    And match response.status == 'REJECTED'

    # 拒絕取消不能改寫已完成的業務結果或 workflow 終態。
    Given path 'demo', 'orders', orderId, 'fulfillment'
    When method get
    Then status 200
    And match response.order.status == 'FULFILLED'
    And match response.temporalWorkflow.outcome == 'FULFILLMENT_COMPLETED'
