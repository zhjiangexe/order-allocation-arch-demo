@events @cancellation @business-flow
Feature: Events 模式在倉內作業前取消 Shipment

  Background:
    * configure retry = { count: 90, interval: 500 }
    * def buildOrderRequest = read('support/build-order-request.js')

  Scenario: Shipment 作業前取消會釋放預留，等待訂單不需補貨即可完成
    # 此 feature 由 runner 以較長 WMS simulation delay 執行，刻意留下可取消的 CREATED 窗口。
    * def sku = 'E2E-EVT-CANCEL-SHIP'
    * def initialStock = call read('support/assert-stock-balance.feature') { sku: '#(sku)', onHand: 10, reserved: 0, available: 10 }
    * def externalOrderNo = 'KARATE-EVT-CANCEL-SHIP-' + newId()
    * def order = buildOrderRequest({ ownerId: ownerId, facilityId: facilityId, externalOrderNo: externalOrderNo, lines: [{ skuCode: sku, quantity: 3 }] })
    Given url orderingUrl
    And path 'orders'
    And request order
    When method post
    Then status 200
    * def orderId = response.orderId

    # simulation delay 讓 CREATED 成為可觀察的穩定窗口，取消必須在倉內作業前送達。
    Given url fulfillmentUrl
    And path 'demo', 'orders', orderId, 'fulfillment'
    And retry until response.shipments.length == 1 && response.shipments[0].status == 'CREATED' && response.order.status == 'ALLOCATED' && response.stockOperation.operation.state == 'ASSIGNED'
    When method get
    Then status 200
    And match response.order.status == 'ALLOCATED'
    And match response.stockOperation.operation.state == 'ASSIGNED'
    And match response.stockOperation.moves == '#[1]'
    And match response.stockOperation.moves[0].state == 'ASSIGNED'
    And match response.stockOperation.moves[0].batches == '#[1]'

    # 第一張已預留 3 件；第二張需要 8 件，現有 ATP 7 不足，必須等待且不能部分預留。
    * def assignedStock = call read('support/assert-stock-balance.feature') { sku: '#(sku)', onHand: 10, reserved: 3, available: 7 }
    * def waitingExternalOrderNo = 'KARATE-EVT-AFTER-CANCEL-' + newId()
    * def waitingOrder = buildOrderRequest({ ownerId: ownerId, facilityId: facilityId, externalOrderNo: waitingExternalOrderNo, lines: [{ skuCode: sku, quantity: 8 }] })
    Given url orderingUrl
    And path 'orders'
    And request waitingOrder
    When method post
    Then status 200
    * def waitingOrderId = response.orderId

    Given url fulfillmentUrl
    And path 'demo', 'orders', waitingOrderId, 'fulfillment'
    And retry until response.stockOperation != null && response.stockOperation.operation.state == 'CONFIRMED' && response.stockOperation.moves.length == 1 && response.stockOperation.moves[0].batches.length == 0
    When method get
    Then status 200
    And match response.order.status == 'PENDING'
    And match response.stockOperation.moves[0] contains { skuCode: '#(sku)', quantity: 8, state: 'CONFIRMED' }
    And match response.shipments == '#[0]'
    * def waitingStock = call read('support/assert-stock-balance.feature') { sku: '#(sku)', onHand: 10, reserved: 3, available: 7 }

    # 這時送出取消，流程必須先撤銷 Shipment，再釋放 move reservation 並取消 Order。
    * def cancellation = { requestId: '#(newId())', requestedAt: '#(now())', reason: '倉內作業開始前取消' }
    Given url fulfillmentUrl
    And path 'orders', orderId, 'cancellation-requests'
    And request cancellation
    When method post
    Then status 202
    And match response.status == 'ACCEPTED'

    # 202 只代表已受理；輪詢直到三個 Context 都完成 compensation。
    Given url fulfillmentUrl
    And path 'demo', 'orders', orderId, 'fulfillment'
    And retry until response.order.status == 'CANCELLED' && response.stockOperation.operation.state == 'CANCELLED' && response.stockOperation.moves[0].state == 'CANCELLED' && response.stockOperation.moves[0].batches.length == 0 && response.shipments[0].status == 'CANCELLED'
    When method get
    Then status 200
    And match response.shipments[0].cancellationState == 'COMPLETED'
    And match response.shipments[0].cancellationRequestId == cancellation.requestId
    And match response.shipments[0].cancellationRequestedAt == cancellation.requestedAt
    And match response.shipments[0].cancellationReason == cancellation.reason
    And match response.shipments[0].cancelledAt == '#string'
    And match response.temporalWorkflow == null

    Given url fulfillmentUrl
    And path 'orders', orderId, 'cancellation-requests', cancellation.requestId
    And retry until response.state == 'COMPLETED'
    When method get
    Then status 200
    And match response.wmsOutcome == 'SHIPMENT_CANCELLED'
    And match response.orderingOutcome == '#string'

    # 原單取消後，等待中的第二張應自行取得庫存。整段流程不收貨、不重送訂單。
    Given url fulfillmentUrl
    And path 'demo', 'orders', waitingOrderId, 'fulfillment'
    And retry until response.order.status == 'ALLOCATED' && response.stockOperation != null && response.stockOperation.operation.state == 'ASSIGNED' && response.shipments.length == 1 && response.shipments[0].status == 'CREATED'
    When method get
    Then status 200
    And match response.stockOperation.moves == '#[1]'
    And match response.stockOperation.moves[0] contains { skuCode: '#(sku)', quantity: 8, state: 'ASSIGNED' }
    And match response.stockOperation.moves[0].batches == '#[1]'
    And match response.stockOperation.moves[0].batches[0].quantity == 8
    * def waitingShipmentId = response.shipments[0].shipmentId
    # 3 件預留已釋放並改由第二張預留 8 件；取消不扣在手量，也不能留下 3 + 8 的預留。
    * def reassignedStock = call read('support/assert-stock-balance.feature') { sku: '#(sku)', onHand: 10, reserved: 8, available: 2 }

    # WMS 有 30 秒 delay，完成等待給 90 秒；輪詢到交運與 Inventory 扣帳都完成。
    * configure retry = { count: 90, interval: 1000 }
    Given url fulfillmentUrl
    And path 'demo', 'orders', waitingOrderId, 'fulfillment'
    And retry until response.order.status == 'FULFILLED' && response.stockOperation.operation.state == 'DONE'
    When method get
    Then status 200
    And match response.stockOperation.moves == '#[1]'
    And match response.stockOperation.moves[0] contains { skuCode: '#(sku)', quantity: 8, state: 'DONE' }
    And match response.shipments == '#[1]'
    And match response.shipments[0] contains { shipmentId: '#(waitingShipmentId)', status: 'HANDED_OVER_TO_CARRIER' }
    And match response.shipments[0].lines == '#[1]'
    And match response.shipments[0].lines[0].quantity == 8
    * def completedStock = call read('support/assert-stock-balance.feature') { sku: '#(sku)', onHand: 2, reserved: 0, available: 2 }

    # 第二張完成不能把原單復活；原單只保留已取消的 Shipment，且沒有 active reservation。
    Given url fulfillmentUrl
    And path 'demo', 'orders', orderId, 'fulfillment'
    When method get
    Then status 200
    And match response.order.status == 'CANCELLED'
    And match response.stockOperation.operation.state == 'CANCELLED'
    And match response.stockOperation.moves[0] contains { state: 'CANCELLED', batches: '#[0]' }
    And match response.shipments == '#[1]'
    And match response.shipments[0].status == 'CANCELLED'
