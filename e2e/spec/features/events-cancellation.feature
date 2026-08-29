@events @cancellation @business-flow
Feature: Events 模式的取消流程

  Background:
    * url baseUrl
    * configure retry = { count: 90, interval: 1000 }
    * def buildOrderRequest = read('support/build-order-request.js')
    * def buildStockReceiptRequest = read('support/build-stock-receipt-request.js')

  Scenario: 待配貨訂單取消後不會再被補貨喚醒，庫存會留給下一張有效訂單
    # 這同時驗證 compensation 與 FIFO：已取消的 stock operation 必須退出競爭，不能卡住後來的訂單。
    * def sku = 'E2E-EVT-CANCEL-PENDING'
    * def cancelledExternalOrderNo = 'KARATE-EVT-CANCEL-PENDING-' + newId()
    * def cancelledOrder = buildOrderRequest({ ownerId: ownerId, facilityId: facilityId, externalOrderNo: cancelledExternalOrderNo, lines: [{ skuCode: sku, quantity: 5 }] })
    Given path 'orders'
    And request cancelledOrder
    When method post
    Then status 200
    * def cancelledOrderId = response.orderId

    # 先確認訂單已穩定進入缺貨等待，且尚未建立 Shipment。
    Given path 'demo', 'orders', cancelledOrderId, 'fulfillment'
    And retry until response.stockOperation != null && response.stockOperation.operation.state == 'CONFIRMED' && response.stockOperation.moves[0].batches.length == 0
    When method get
    Then status 200

    # 取消 API 只接受命令；實際 compensation 仍由非同步流程完成。
    * def cancellation = { requestId: '#(newId())', requestedAt: '#(now())', reason: '客戶在待配貨時取消' }
    Given path 'orders', cancelledOrderId, 'cancellation-requests'
    And request cancellation
    When method post
    Then status 202
    And match response.status == 'ACCEPTED'
    And match response.effectiveRequestId == cancellation.requestId

    # 等待 Ordering 與 Inventory 都完成取消，避免只驗到命令已受理。
    Given path 'demo', 'orders', cancelledOrderId, 'fulfillment'
    And retry until response.order.status == 'CANCELLED' && response.stockOperation.operation.state == 'CANCELLED' && response.stockOperation.moves[0].state == 'CANCELLED'
    When method get
    Then status 200
    And match response.stockOperation.moves[0].batches == '#[0]'
    And match response.shipments == '#[0]'

    # 網路 retry 重送完全相同的取消命令時，只回報既有結果，不重做 compensation。
    Given path 'orders', cancelledOrderId, 'cancellation-requests'
    And request cancellation
    When method post
    Then status 200
    And match response.status == 'ALREADY_CANCELLED'
    And match response.effectiveRequestId == cancellation.requestId

    # 同一 SKU 的下一張有效訂單應能取得庫存，證明已取消的 stock operation 不再阻塞 FIFO。
    * def liveExternalOrderNo = 'KARATE-EVT-AFTER-CANCEL-' + newId()
    * def liveOrder = buildOrderRequest({ ownerId: ownerId, facilityId: facilityId, externalOrderNo: liveExternalOrderNo, lines: [{ skuCode: sku, quantity: 5 }] })
    Given path 'orders'
    And request liveOrder
    When method post
    Then status 200
    * def liveOrderId = response.orderId
    Given path 'demo', 'orders', liveOrderId, 'fulfillment'
    And retry until response.stockOperation != null && response.stockOperation.operation.state == 'CONFIRMED' && response.stockOperation.moves[0].batches.length == 0
    When method get
    Then status 200

    * def receiptId = newId()
    * def receipt = buildStockReceiptRequest({ receiptId: receiptId, ownerId: ownerId, facilityId: facilityId, locationId: locationId, sku: sku, quantity: 5 })
    Given path 'stock-receipts'
    And request receipt
    When method post
    Then status 200
    Given path 'demo', 'orders', liveOrderId, 'fulfillment'
    And retry until response.order.status == 'FULFILLED'
    When method get
    Then status 200

    # 最後再確認後續履約沒有把原訂單從 CANCELLED 誤改回其他狀態。
    Given path 'orders', cancelledOrderId
    When method get
    Then status 200
    And match response.status == 'CANCELLED'

  Scenario: 已交運完成的訂單拒絕取消，改由退貨流程處理
    # handover 是不可逆邊界；取消 endpoint 應回 409，且不能改掉既有 FULFILLED 狀態。
    * def externalOrderNo = 'KARATE-EVT-CANCEL-DONE-' + newId()
    * def order = buildOrderRequest({ ownerId: ownerId, facilityId: facilityId, externalOrderNo: externalOrderNo, lines: [{ skuCode: 'E2E-EVT-CANCEL-DONE', quantity: 2 }] })
    Given path 'orders'
    And request order
    When method post
    Then status 200
    * def orderId = response.orderId

    # 先跨過 handover 不可逆邊界，再送出取消命令。
    Given path 'demo', 'orders', orderId, 'fulfillment'
    And retry until response.order.status == 'FULFILLED'
    When method get
    Then status 200

    * def cancellation = { requestId: '#(newId())', requestedAt: '#(now())', reason: '交運後要求取消' }
    Given path 'orders', orderId, 'cancellation-requests'
    And request cancellation
    When method post
    Then status 409
    And match response.status == 'REJECTED'

    # 拒絕取消不能破壞已完成的訂單與 Shipment 狀態。
    Given path 'demo', 'orders', orderId, 'fulfillment'
    When method get
    Then status 200
    And match response.order.status == 'FULFILLED'
    And match response.shipments[0].status == 'HANDED_OVER_TO_CARRIER'
