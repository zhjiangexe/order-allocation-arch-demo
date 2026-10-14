@temporal @cancellation @business-flow
Feature: Temporal workflow 的取消分支

  Background:
    * configure retry = { count: 90, interval: 500 }
    * def buildOrderRequest = read('support/build-order-request.js')

  Scenario: 尚未配到庫存時，workflow 不需 Shipment 就能取消訂單
    # 此分支證明取消不是依賴 Shipment 存在；workflow 應直接協調 Ordering 與 Inventory 結束等待。
    * def externalOrderNo = 'KARATE-TMP-CANCEL-PENDING-' + newId()
    * def order = buildOrderRequest({ ownerId: ownerId, facilityId: facilityId, externalOrderNo: externalOrderNo, lines: [{ skuCode: 'E2E-TMP-CANCEL-PENDING', quantity: 2 }] })
    Given url orderingUrl
    And path 'orders'
    And request order
    When method post
    Then status 200
    * def orderId = response.orderId

    # 先確認 workflow 正在等待 assignment，且 stock operation 尚未保留 batch、也未建立 Shipment。
    Given url fulfillmentUrl
    And path 'demo', 'orders', orderId, 'fulfillment'
    And retry until response.stockOperation != null && response.stockOperation.operation.state == 'CONFIRMED' && response.stockOperation.moves[0].batches.length == 0 && response.temporalWorkflow != null
    When method get
    Then status 200
    And match response.shipments == '#[0]'

    # 取消命令會 Update 原 workflow，而不是另起一條補償流程。
    * def cancellation = { requestId: '#(newId())', requestedAt: '#(now())', reason: 'Temporal 待配貨取消' }
    Given url fulfillmentUrl
    And path 'orders', orderId, 'cancellation-requests'
    And request cancellation
    When method post
    Then status 202
    And match response.status == 'ACCEPTED'

    # 等待 workflow、Ordering 與 Inventory 一起收斂到取消終態。
    Given url fulfillmentUrl
    And path 'demo', 'orders', orderId, 'fulfillment'
    And retry until response.order.status == 'CANCELLED' && response.stockOperation.operation.state == 'CANCELLED' && response.stockOperation.moves[0].state == 'CANCELLED' && response.temporalWorkflow.outcome == 'ORDER_CANCELLED'
    When method get
    Then status 200
    And match response.shipments == '#[0]'
    And match response.temporalWorkflow.phase == 'FINISHED'
    And match response.temporalWorkflow.cancellationState == 'ORDER_CANCELLED'
    And match response.temporalWorkflow.cancellationRequestId == cancellation.requestId

  Scenario: workflow 取消 Shipment 後釋放預留，等待訂單不需補貨即可完成
    # runner 以較長 WMS simulation delay 執行，讓 cancellation signal 能落在 CREATED 狀態。
    * def sku = 'E2E-TMP-CANCEL-SHIP'
    * def initialStock = call read('support/assert-stock-balance.feature') { sku: '#(sku)', onHand: 10, reserved: 0, available: 10 }
    * def externalOrderNo = 'KARATE-TMP-CANCEL-SHIP-' + newId()
    * def order = buildOrderRequest({ ownerId: ownerId, facilityId: facilityId, externalOrderNo: externalOrderNo, lines: [{ skuCode: sku, quantity: 3 }] })
    Given url orderingUrl
    And path 'orders'
    And request order
    When method post
    Then status 200
    * def orderId = response.orderId

    # simulation delay 讓 CREATED 成為可觀察窗口，signal 必須在倉內作業前送達。
    Given url fulfillmentUrl
    And path 'demo', 'orders', orderId, 'fulfillment'
    And retry until response.shipments.length == 1 && response.shipments[0].status == 'CREATED' && response.temporalWorkflow != null
    When method get
    Then status 200

    # 第一張已預留 3 件；第二張需要 8 件，現有 ATP 7 不足，必須等待且不能部分預留。
    * def assignedStock = call read('support/assert-stock-balance.feature') { sku: '#(sku)', onHand: 10, reserved: 3, available: 7 }
    * def waitingExternalOrderNo = 'KARATE-TMP-AFTER-CANCEL-' + newId()
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

    # 暫停 terminal event 傳遞，保留真實 Workflow 的取消等待窗口，避免測到已取消 Order 的捷徑。
    Given url connectUrl
    And path 'connectors', connectorName, 'pause'
    When method put
    Then status 202

    Given url connectUrl
    And path 'connectors', connectorName, 'status'
    And retry until response.connector.state == 'PAUSED' && response.tasks.length > 0 && response.tasks.every(x => x.state == 'PAUSED')
    When method get
    Then status 200

    # 取消命令 Update 同一條 workflow，由它協調 Shipment 與上游狀態回補。
    * def cancellation = { requestId: '#(newId())', requestedAt: '#(now())', reason: 'Temporal 倉內作業前取消' }
    Given url fulfillmentUrl
    And path 'orders', orderId, 'cancellation-requests'
    And request cancellation
    When method post
    Then status 202
    And match response.status == 'ACCEPTED'

    # 同一 requestId 改 reason 或 requestedAt，都必須由真實 Temporal Update 回報衝突並映射成 HTTP 409。
    * copy changedReason = cancellation
    * set changedReason.reason = '不同的取消原因'
    Given url fulfillmentUrl
    And path 'orders', orderId, 'cancellation-requests'
    And request changedReason
    When method post
    Then status 409
    And match response.status == 'CONFLICT'
    And match response.effectiveRequestId == cancellation.requestId

    * copy changedTime = cancellation
    * set changedTime.requestedAt = '2000-01-01T00:00:00Z'
    Given url fulfillmentUrl
    And path 'orders', orderId, 'cancellation-requests'
    And request changedTime
    When method post
    Then status 409
    And match response.status == 'CONFLICT'
    And match response.effectiveRequestId == cancellation.requestId

    # 原始請求仍可冪等重送，且流程尚未完成取消。
    Given url fulfillmentUrl
    And path 'orders', orderId, 'cancellation-requests'
    And request cancellation
    When method post
    Then status 200
    And match response.status == 'ALREADY_REQUESTED'
    And match response.effectiveRequestId == cancellation.requestId

    Given url fulfillmentUrl
    And path 'demo', 'orders', orderId, 'fulfillment'
    When method get
    Then status 200
    And match response.order.status != 'CANCELLED'
    And match response.temporalWorkflow.cancellationState == 'REQUESTED'
    And match response.temporalWorkflow.cancellationRequestedAt == cancellation.requestedAt

    Given url connectUrl
    And path 'connectors', connectorName, 'resume'
    When method put
    Then status 202

    # 202 只代表已受理；輪詢直到 workflow 與三個業務狀態都完成 compensation。
    Given url fulfillmentUrl
    And path 'demo', 'orders', orderId, 'fulfillment'
    And retry until response.order.status == 'CANCELLED' && response.stockOperation.operation.state == 'CANCELLED' && response.stockOperation.moves[0].state == 'CANCELLED' && response.stockOperation.moves[0].batches.length == 0 && response.shipments[0].status == 'CANCELLED' && response.temporalWorkflow.outcome == 'ORDER_CANCELLED'
    When method get
    Then status 200
    And match response.shipments[0].cancellationState == 'COMPLETED'
    And match response.shipments[0].cancellationRequestId == cancellation.requestId
    And match response.shipments[0].cancellationRequestedAt == cancellation.requestedAt
    And match response.shipments[0].cancellationReason == cancellation.reason
    And match response.shipments[0].cancelledAt == '#string'
    And match response.temporalWorkflow.phase == 'FINISHED'
    And match response.temporalWorkflow.cancellationState == 'ORDER_CANCELLED'

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
    And retry until response.order.status == 'FULFILLED' && response.stockOperation.operation.state == 'DONE' && response.temporalWorkflow != null && response.temporalWorkflow.outcome == 'FULFILLMENT_COMPLETED'
    When method get
    Then status 200
    And match response.stockOperation.moves == '#[1]'
    And match response.stockOperation.moves[0] contains { skuCode: '#(sku)', quantity: 8, state: 'DONE' }
    And match response.shipments == '#[1]'
    And match response.shipments[0] contains { shipmentId: '#(waitingShipmentId)', status: 'HANDED_OVER_TO_CARRIER' }
    And match response.shipments[0].lines == '#[1]'
    And match response.shipments[0].lines[0].quantity == 8
    And match response.temporalWorkflow.phase == 'FINISHED'
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
