@events @business-flow
Feature: Events 模式的配貨與履約流程

  Background:
    * url baseUrl
    * configure retry = { count: 90, interval: 1000 }
    * def buildOrderRequest = read('support/build-order-request.js')
    * def buildStockReceiptRequest = read('support/build-stock-receipt-request.js')

  Scenario: 有足夠庫存時，訂單會走完配貨、倉內作業與交運
    # 這是 Events orchestration 的主成功路徑；最終狀態不足以證明流程完整，所以一併檢查 stock operation 與 shipment。
    * def externalOrderNo = 'KARATE-EVT-HAPPY-' + newId()
    * def order = buildOrderRequest({ ownerId: ownerId, facilityId: facilityId, externalOrderNo: externalOrderNo, lines: [{ skuCode: 'E2E-EVT-HAPPY', quantity: 3 }] })
    Given path 'orders'
    And request order
    When method post
    Then status 200
    And match response.orderId == '#uuid'
    * def orderId = response.orderId

    # POST 只完成下單；輪詢跨 Context 的 read model，確認整條履約鏈已收斂。
    Given path 'demo', 'orders', orderId, 'fulfillment'
    And retry until response.order.status == 'FULFILLED'
    When method get
    Then status 200
    And match response.orchestrationMode == 'events'
    And match response.workflowQueryStatus == 'NOT_APPLICABLE'
    And match response.temporalWorkflow == null
    And match response.order.status == 'FULFILLED'
    And match response.stockOperation.source == { type: 'ORDER', sourceId: '#(orderId)', operationUnitKey: 'PRIMARY' }
    And match response.stockOperation.operation.state == 'DONE'
    And match response.stockOperation.moves == '#[1]'
    And match response.stockOperation.moves[0] contains { skuCode: 'E2E-EVT-HAPPY', quantity: 3, state: 'DONE' }
    And match response.stockOperation.moves[0].batches == '#[1]'
    And match response.stockOperation.moves[0].batches[0].quantity == 3
    And match response.shipments == '#[1]'
    And match response.shipments[0].status == 'HANDED_OVER_TO_CARRIER'
    And match response.shipments[0].waveId == '#uuid'
    And match response.shipments[0].lines[0].quantity == 3
    And match response.orchestrationMode == 'events'
    And match response.workflowQueryStatus == 'NOT_APPLICABLE'
    And match response.temporalWorkflow == null

  Scenario: 缺貨訂單會等待，收貨後由 availability event 喚醒並完成
    # CONFIRMED stock operation 是可恢復狀態；補貨不需重送訂單，也不由測試直接呼叫 assignment command。
    * def sku = 'E2E-EVT-WAKE'
    * def externalOrderNo = 'KARATE-EVT-WAKE-' + newId()
    * def order = buildOrderRequest({ ownerId: ownerId, facilityId: facilityId, externalOrderNo: externalOrderNo, lines: [{ skuCode: sku, quantity: 4 }] })
    Given path 'orders'
    And request order
    When method post
    Then status 200
    * def orderId = response.orderId

    # 先觀察穩定的 CONFIRMED checkpoint：move 已表達需求，但尚無 batch reservation 或 Shipment。
    Given path 'demo', 'orders', orderId, 'fulfillment'
    And retry until response.stockOperation != null && response.stockOperation.operation.state == 'CONFIRMED' && response.stockOperation.moves.length == 1 && response.stockOperation.moves[0].batches.length == 0
    When method get
    Then status 200
    And match response.order.status == 'PENDING'
    And match response.stockOperation.moves[0] contains { skuCode: '#(sku)', quantity: 4, state: 'CONFIRMED' }
    And match response.shipments == '#[0]'

    # 收貨是唯一的恢復命令；測試不直接介入 assignment。
    * def receiptId = newId()
    * def receipt = buildStockReceiptRequest({ receiptId: receiptId, ownerId: ownerId, facilityId: facilityId, locationId: locationId, sku: sku, quantity: 4 })
    Given path 'stock-receipts'
    And request receipt
    When method post
    Then status 200

    # 不重送訂單，等待 availability event 喚醒原本的 stock operation。
    Given path 'demo', 'orders', orderId, 'fulfillment'
    And retry until response.order.status == 'FULFILLED'
    When method get
    Then status 200
    And match response.stockOperation.operation.state == 'DONE'
    And match response.stockOperation.moves[0].state == 'DONE'
    And match response.stockOperation.moves[0].batches == '#[1]'
    And match response.shipments[0].status == 'HANDED_OVER_TO_CARRIER'

  Scenario: ship-complete 會等所有 SKU 都足夠，不會先占用充足的那一行
    # A 有庫存、B 沒庫存時整張訂單保持 PENDING；B 補齊後兩行才一起進入履約。
    * def skuA = 'E2E-EVT-MULTI-A'
    * def skuB = 'E2E-EVT-MULTI-B'
    * def externalOrderNo = 'KARATE-EVT-MULTI-' + newId()
    * def order = buildOrderRequest({ ownerId: ownerId, facilityId: facilityId, externalOrderNo: externalOrderNo, lines: [{ skuCode: skuA, quantity: 5 }, { skuCode: skuB, quantity: 4 }] })
    Given path 'orders'
    And request order
    When method post
    Then status 200
    * def orderId = response.orderId

    # 先確認整張訂單都在等待，而且兩行都尚未保留庫存。
    Given path 'demo', 'orders', orderId, 'fulfillment'
    And retry until response.stockOperation != null && response.stockOperation.operation.state == 'CONFIRMED' && response.stockOperation.moves.length == 2 && response.stockOperation.moves[0].batches.length == 0 && response.stockOperation.moves[1].batches.length == 0
    When method get
    Then status 200
    And match response.stockOperation.moves contains deep { skuCode: '#(skuA)', quantity: 5, state: 'CONFIRMED', batches: '#[0]' }
    And match response.stockOperation.moves contains deep { skuCode: '#(skuB)', quantity: 4, state: 'CONFIRMED', batches: '#[0]' }
    # move 是來源需求的 canonical intent；ship-complete 成功前只是不建立任何 batch reservation。
    And match response.shipments == '#[0]'

    # 只補齊缺貨的 B，A 使用原有庫存；兩行仍必須一起進入履約。
    * def receiptId = newId()
    * def receipt = buildStockReceiptRequest({ receiptId: receiptId, ownerId: ownerId, facilityId: facilityId, locationId: locationId, sku: skuB, quantity: 4 })
    Given path 'stock-receipts'
    And request receipt
    When method post
    Then status 200

    # 原訂單應自行恢復，並以完整兩個 move 完成 assignment 與 shipment。
    Given path 'demo', 'orders', orderId, 'fulfillment'
    And retry until response.order.status == 'FULFILLED'
    When method get
    Then status 200
    And match response.order.lines == '#[2]'
    And match response.stockOperation.operation.state == 'DONE'
    And match response.stockOperation.moves == '#[2]'
    And match each response.stockOperation.moves contains { state: 'DONE' }
    And match response.shipments[0].lines == '#[2]'

  Scenario: 同一 SKU 出現在兩行時會先加總需求，再一次判斷整張訂單
    # 兩行不是兩張獨立需求；總需求 5 必須完整保留到 allocation 與 shipment 的明細中。
    * def sku = 'E2E-EVT-SAME-SKU'
    * def externalOrderNo = 'KARATE-EVT-SAME-SKU-' + newId()
    * def order = buildOrderRequest({ ownerId: ownerId, facilityId: facilityId, externalOrderNo: externalOrderNo, lines: [{ skuCode: sku, quantity: 2 }, { skuCode: sku, quantity: 3 }] })
    Given path 'orders'
    And request order
    When method post
    Then status 200
    * def orderId = response.orderId

    # 最終 read model 同時驗證總需求與原始行明細都被保留下來。
    Given path 'demo', 'orders', orderId, 'fulfillment'
    And retry until response.order.status == 'FULFILLED'
    When method get
    Then status 200
    And match response.stockOperation.moves == '#[2]'
    And match each response.stockOperation.moves contains { skuCode: '#(sku)', state: 'DONE' }
    * def moveQuantities = response.stockOperation.moves.map(x => x.quantity)
    And match moveQuantities contains only [2, 3]
    And match response.shipments[0].lines == '#[2]'
    * def quantities = response.shipments[0].lines.map(x => x.quantity)
    And match quantities contains only [2, 3]

  Scenario: FIFO 的隊首不足時不能跳過它配置後來的小單
    # 第一張要 5、第二張只要 2；先補 2 時兩張都應等待，避免後來的小單插隊。
    * def sku = 'E2E-EVT-FIFO'
    * def firstExternalOrderNo = 'KARATE-EVT-FIFO-FIRST-' + newId()
    * def firstOrder = buildOrderRequest({ ownerId: ownerId, facilityId: facilityId, externalOrderNo: firstExternalOrderNo, lines: [{ skuCode: sku, quantity: 5 }] })
    Given path 'orders'
    And request firstOrder
    When method post
    Then status 200
    * def firstOrderId = response.orderId

    # 先讓第一張大單成為穩定的 FIFO 隊首。
    Given path 'demo', 'orders', firstOrderId, 'fulfillment'
    And retry until response.stockOperation != null && response.stockOperation.operation.state == 'CONFIRMED' && response.stockOperation.moves.length == 1 && response.stockOperation.moves[0].batches.length == 0
    When method get
    Then status 200

    # 隊首建立後才送第二張小單，讓先後順序明確可驗證。
    * def secondExternalOrderNo = 'KARATE-EVT-FIFO-SECOND-' + newId()
    * def secondOrder = buildOrderRequest({ ownerId: ownerId, facilityId: facilityId, externalOrderNo: secondExternalOrderNo, lines: [{ skuCode: sku, quantity: 2 }] })
    Given path 'orders'
    And request secondOrder
    When method post
    Then status 200
    * def secondOrderId = response.orderId
    Given path 'demo', 'orders', secondOrderId, 'fulfillment'
    And retry until response.stockOperation != null && response.stockOperation.operation.state == 'CONFIRMED' && response.stockOperation.moves.length == 1 && response.stockOperation.moves[0].batches.length == 0
    When method get
    Then status 200

    # 先補 2：數量足以完成第二張，卻不足以完成隊首。
    * def firstReceiptId = newId()
    * def firstReceipt = buildStockReceiptRequest({ receiptId: firstReceiptId, ownerId: ownerId, facilityId: facilityId, locationId: locationId, sku: sku, quantity: 2 })
    Given path 'stock-receipts'
    And request firstReceipt
    When method post
    Then status 200
    * karate.pause(2000)

    Given path 'orders', firstOrderId
    When method get
    Then status 200
    And match response.status == 'PENDING'
    Given path 'orders', secondOrderId
    When method get
    Then status 200
    And match response.status == 'PENDING'

    # 補到隊首需要的 5 後，只能先完成第一張；第二張仍需等自己的庫存。
    * def secondReceiptId = newId()
    * def secondReceipt = buildStockReceiptRequest({ receiptId: secondReceiptId, ownerId: ownerId, facilityId: facilityId, locationId: locationId, sku: sku, quantity: 3 })
    Given path 'stock-receipts'
    And request secondReceipt
    When method post
    Then status 200
    Given path 'demo', 'orders', firstOrderId, 'fulfillment'
    And retry until response.order.status == 'FULFILLED'
    When method get
    Then status 200
    Given path 'orders', secondOrderId
    When method get
    Then status 200
    And match response.status == 'PENDING'

    # 第一張完成後再補第二張自己的 2，確認 FIFO 沒有造成永久阻塞。
    * def thirdReceiptId = newId()
    * def thirdReceipt = buildStockReceiptRequest({ receiptId: thirdReceiptId, ownerId: ownerId, facilityId: facilityId, locationId: locationId, sku: sku, quantity: 2 })
    Given path 'stock-receipts'
    And request thirdReceipt
    When method post
    Then status 200
    Given path 'demo', 'orders', secondOrderId, 'fulfillment'
    And retry until response.order.status == 'FULFILLED'
    When method get
    Then status 200
