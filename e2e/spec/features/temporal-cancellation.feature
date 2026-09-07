@temporal @cancellation @business-flow
Feature: Temporal workflow 的取消分支

  Background:
    * url baseUrl
    * configure retry = { count: 90, interval: 500 }
    * def buildOrderRequest = read('support/build-order-request.js')

  Scenario: 尚未配到庫存時，workflow 不需 Shipment 就能取消訂單
    # 此分支證明取消不是依賴 Shipment 存在；workflow 應直接協調 Ordering 與 Inventory 結束等待。
    * def externalOrderNo = 'KARATE-TMP-CANCEL-PENDING-' + newId()
    * def order = buildOrderRequest({ ownerId: ownerId, facilityId: facilityId, externalOrderNo: externalOrderNo, lines: [{ skuCode: 'E2E-TMP-CANCEL-PENDING', quantity: 2 }] })
    Given path 'orders'
    And request order
    When method post
    Then status 200
    * def orderId = response.orderId

    # 先確認 workflow 正在等待 assignment，且 stock operation 尚未保留 batch、也未建立 Shipment。
    Given path 'demo', 'orders', orderId, 'fulfillment'
    And retry until response.stockOperation != null && response.stockOperation.operation.state == 'CONFIRMED' && response.stockOperation.moves[0].batches.length == 0 && response.temporalWorkflow != null
    When method get
    Then status 200
    And match response.shipments == '#[0]'

    # 取消命令會 Update 原 workflow，而不是另起一條補償流程。
    * def cancellation = { requestId: '#(newId())', requestedAt: '#(now())', reason: 'Temporal 待配貨取消' }
    Given path 'orders', orderId, 'cancellation-requests'
    And request cancellation
    When method post
    Then status 202
    And match response.status == 'ACCEPTED'

    # 等待 workflow、Ordering 與 Inventory 一起收斂到取消終態。
    Given path 'demo', 'orders', orderId, 'fulfillment'
    And retry until response.order.status == 'CANCELLED' && response.stockOperation.operation.state == 'CANCELLED' && response.stockOperation.moves[0].state == 'CANCELLED' && response.temporalWorkflow.outcome == 'ORDER_CANCELLED'
    When method get
    Then status 200
    And match response.shipments == '#[0]'
    And match response.temporalWorkflow.phase == 'FINISHED'
    And match response.temporalWorkflow.cancellationState == 'ORDER_CANCELLED'
    And match response.temporalWorkflow.cancellationRequestId == cancellation.requestId

  Scenario: Shipment 已建立但未開始倉內作業時，workflow 先取消 Shipment 再取消 Order
    # runner 以較長 WMS simulation delay 執行，讓 cancellation signal 能落在 CREATED 狀態。
    * def externalOrderNo = 'KARATE-TMP-CANCEL-SHIP-' + newId()
    * def order = buildOrderRequest({ ownerId: ownerId, facilityId: facilityId, externalOrderNo: externalOrderNo, lines: [{ skuCode: 'E2E-TMP-CANCEL-SHIP', quantity: 3 }] })
    Given path 'orders'
    And request order
    When method post
    Then status 200
    * def orderId = response.orderId

    # simulation delay 讓 CREATED 成為可觀察窗口，signal 必須在倉內作業前送達。
    Given path 'demo', 'orders', orderId, 'fulfillment'
    And retry until response.shipments.length == 1 && response.shipments[0].status == 'CREATED' && response.temporalWorkflow != null
    When method get
    Then status 200

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

    Given url baseUrl
    # 取消命令 Update 同一條 workflow，由它協調 Shipment 與上游狀態回補。
    * def cancellation = { requestId: '#(newId())', requestedAt: '#(now())', reason: 'Temporal 倉內作業前取消' }
    Given path 'orders', orderId, 'cancellation-requests'
    And request cancellation
    When method post
    Then status 202
    And match response.status == 'ACCEPTED'

    # 同一 requestId 改 reason 或 requestedAt，都必須由真實 Temporal Update 回報衝突並映射成 HTTP 409。
    * copy changedReason = cancellation
    * set changedReason.reason = '不同的取消原因'
    Given path 'orders', orderId, 'cancellation-requests'
    And request changedReason
    When method post
    Then status 409
    And match response.status == 'CONFLICT'
    And match response.effectiveRequestId == cancellation.requestId

    * copy changedTime = cancellation
    * set changedTime.requestedAt = '2000-01-01T00:00:00Z'
    Given path 'orders', orderId, 'cancellation-requests'
    And request changedTime
    When method post
    Then status 409
    And match response.status == 'CONFLICT'
    And match response.effectiveRequestId == cancellation.requestId

    # 原始請求仍可冪等重送，且流程尚未完成取消。
    Given path 'orders', orderId, 'cancellation-requests'
    And request cancellation
    When method post
    Then status 200
    And match response.status == 'ALREADY_REQUESTED'
    And match response.effectiveRequestId == cancellation.requestId

    Given path 'demo', 'orders', orderId, 'fulfillment'
    When method get
    Then status 200
    And match response.order.status != 'CANCELLED'
    And match response.temporalWorkflow.cancellationState == 'REQUESTED'
    And match response.temporalWorkflow.cancellationRequestedAt == cancellation.requestedAt

    Given url connectUrl
    And path 'connectors', connectorName, 'resume'
    When method put
    Then status 202

    Given url baseUrl
    # 202 只代表已受理；輪詢直到 workflow 與三個業務狀態都完成 compensation。
    Given path 'demo', 'orders', orderId, 'fulfillment'
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
