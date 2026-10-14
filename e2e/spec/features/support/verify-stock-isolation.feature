@ignore
Feature: 同 SKU 的其他貨主或倉庫不得供應目標訂單

  Scenario:
    * def buildOrderRequest = read('build-order-request.js')
    * def buildStockReceiptRequest = read('build-stock-receipt-request.js')
    * def initialTargetStock = call read('assert-stock-balance.feature') { sku: '#(sku)', onHand: 2, reserved: 0, available: 2 }
    * def initialSupplyStock = call read('assert-stock-balance.feature') { sku: '#(sku)', ownerId: '#(supplyOwnerId)', locationId: '#(supplyLocationId)', onHand: 7, reserved: 0, available: 7 }

    # 目標要 4 件，本身只有 2 件；其他範圍已有 7 件也不得拿來配置或部分預留。
    * def targetExternalOrderNo = 'KARATE-ISOLATION-TARGET-' + newId()
    * def targetOrder = buildOrderRequest({ ownerId: ownerId, facilityId: facilityId, externalOrderNo: targetExternalOrderNo, lines: [{ skuCode: sku, quantity: 4 }] })
    Given url orderingUrl
    And path 'orders'
    And request targetOrder
    When method post
    Then status 200
    * def targetOrderId = response.orderId

    Given url fulfillmentUrl
    And path 'demo', 'orders', targetOrderId, 'fulfillment'
    And retry until response.stockOperation != null && response.stockOperation.operation.state == 'CONFIRMED' && response.stockOperation.moves.length == 1 && response.stockOperation.moves[0].batches.length == 0
    When method get
    Then status 200
    And match response.order contains { ownerId: '#(ownerId)', facilityId: '#(facilityId)', status: 'PENDING' }
    And match response.stockOperation.moves[0] contains { skuCode: '#(sku)', quantity: 4, state: 'CONFIRMED' }
    And match response.shipments == '#[0]'

    # 其他範圍也建立缺貨的控制訂單。後續以它的成功履約，確認補貨確實已被非同步流程處理。
    * def controlExternalOrderNo = 'KARATE-ISOLATION-CONTROL-' + newId()
    * def controlOrder = buildOrderRequest({ ownerId: supplyOwnerId, facilityId: supplyFacilityId, externalOrderNo: controlExternalOrderNo, lines: [{ skuCode: sku, quantity: 9 }] })
    Given url orderingUrl
    And path 'orders'
    And request controlOrder
    When method post
    Then status 200
    * def controlOrderId = response.orderId

    Given url fulfillmentUrl
    And path 'demo', 'orders', controlOrderId, 'fulfillment'
    And retry until response.stockOperation != null && response.stockOperation.operation.state == 'CONFIRMED' && response.stockOperation.moves.length == 1 && response.stockOperation.moves[0].batches.length == 0
    When method get
    Then status 200
    And match response.order contains { ownerId: '#(supplyOwnerId)', facilityId: '#(supplyFacilityId)', status: 'PENDING' }
    And match response.shipments == '#[0]'
    * def waitingTargetStock = call read('assert-stock-balance.feature') { sku: '#(sku)', onHand: 2, reserved: 0, available: 2 }
    * def waitingSupplyStock = call read('assert-stock-balance.feature') { sku: '#(sku)', ownerId: '#(supplyOwnerId)', locationId: '#(supplyLocationId)', onHand: 7, reserved: 0, available: 7 }

    # 補 5 件到其他範圍；只足以讓該範圍的控制訂單出貨，不能喚醒目標訂單完成配貨。
    * def supplyReceiptId = newId()
    * def supplyReceipt = buildStockReceiptRequest({ receiptId: supplyReceiptId, ownerId: supplyOwnerId, facilityId: supplyFacilityId, locationId: supplyLocationId, sku: sku, quantity: 5 })
    Given url inventoryUrl
    And path 'stock-receipts'
    And request supplyReceipt
    When method post
    Then status 200
    And match response == { receiptId: '#(supplyReceiptId)', sku: '#(sku)', quantity: 5 }

    Given url fulfillmentUrl
    And path 'demo', 'orders', controlOrderId, 'fulfillment'
    And retry until response.order.status == 'FULFILLED' && response.stockOperation != null && response.stockOperation.operation.state == 'DONE' && (!isTemporal || (response.temporalWorkflow != null && response.temporalWorkflow.outcome == 'FULFILLMENT_COMPLETED'))
    When method get
    Then status 200
    And match response.order contains { ownerId: '#(supplyOwnerId)', facilityId: '#(supplyFacilityId)' }
    And match response.stockOperation.operation contains { ownerId: '#(supplyOwnerId)', fromLocationId: '#(supplyLocationId)' }
    And match response.stockOperation.moves == '#[1]'
    And match response.stockOperation.moves[0] contains { skuCode: '#(sku)', quantity: 9, state: 'DONE' }
    And match response.stockOperation.moves[0].batches == '#[1]'
    And match response.stockOperation.moves[0].batches[0] contains { stockQuantId: '#(initialSupplyStock.skuStocks[0].batches[0].stockPoolId)', locationId: '#(supplyLocationId)', quantity: 9 }
    And match response.shipments == '#[1]'
    And match response.shipments[0].status == 'HANDED_OVER_TO_CARRIER'
    And match response.shipments[0].lines == '#[1]'
    And match response.shipments[0].lines[0].quantity == 9
    And match response.workflowQueryStatus == (isTemporal ? 'AVAILABLE' : 'NOT_APPLICABLE')

    # 7 + 5 - 9 = 3，目標仍有 2 件；不能只看訂單狀態而漏掉其他範圍被錯扣的庫存。
    * def consumedSupplyStock = call read('assert-stock-balance.feature') { sku: '#(sku)', ownerId: '#(supplyOwnerId)', locationId: '#(supplyLocationId)', onHand: 3, reserved: 0, available: 3 }
    * def unchangedTargetStock = call read('assert-stock-balance.feature') { sku: '#(sku)', onHand: 2, reserved: 0, available: 2 }
    Given url fulfillmentUrl
    And path 'demo', 'orders', targetOrderId, 'fulfillment'
    When method get
    Then status 200
    And match response.order.status == 'PENDING'
    And match response.stockOperation.operation.state == 'CONFIRMED'
    And match response.stockOperation.moves == '#[1]'
    And match response.stockOperation.moves[0] contains { skuCode: '#(sku)', quantity: 4, state: 'CONFIRMED', batches: '#[0]' }
    And match response.shipments == '#[0]'

    # 只有補到目標貨主與倉庫的 2 件，才可讓原訂單自行完成；不重送下單命令。
    * def targetReceiptId = newId()
    * def targetReceipt = buildStockReceiptRequest({ receiptId: targetReceiptId, ownerId: ownerId, facilityId: facilityId, locationId: locationId, sku: sku, quantity: 2 })
    Given url inventoryUrl
    And path 'stock-receipts'
    And request targetReceipt
    When method post
    Then status 200
    And match response == { receiptId: '#(targetReceiptId)', sku: '#(sku)', quantity: 2 }

    Given url fulfillmentUrl
    And path 'demo', 'orders', targetOrderId, 'fulfillment'
    And retry until response.order.status == 'FULFILLED' && response.stockOperation != null && response.stockOperation.operation.state == 'DONE' && (!isTemporal || (response.temporalWorkflow != null && response.temporalWorkflow.outcome == 'FULFILLMENT_COMPLETED'))
    When method get
    Then status 200
    And match response.order contains { ownerId: '#(ownerId)', facilityId: '#(facilityId)' }
    And match response.stockOperation.operation contains { ownerId: '#(ownerId)', fromLocationId: '#(locationId)' }
    And match response.stockOperation.moves == '#[1]'
    And match response.stockOperation.moves[0] contains { skuCode: '#(sku)', quantity: 4, state: 'DONE' }
    And match response.stockOperation.moves[0].batches == '#[1]'
    And match response.stockOperation.moves[0].batches[0] contains { stockQuantId: '#(initialTargetStock.skuStocks[0].batches[0].stockPoolId)', locationId: '#(locationId)', quantity: 4 }
    And match response.shipments == '#[1]'
    And match response.shipments[0].status == 'HANDED_OVER_TO_CARRIER'
    And match response.shipments[0].lines == '#[1]'
    And match response.shipments[0].lines[0].quantity == 4
    And match response.workflowQueryStatus == (isTemporal ? 'AVAILABLE' : 'NOT_APPLICABLE')
    * def completedTargetStock = call read('assert-stock-balance.feature') { sku: '#(sku)', onHand: 0, reserved: 0, available: 0 }
    * def preservedSupplyStock = call read('assert-stock-balance.feature') { sku: '#(sku)', ownerId: '#(supplyOwnerId)', locationId: '#(supplyLocationId)', onHand: 3, reserved: 0, available: 3 }
