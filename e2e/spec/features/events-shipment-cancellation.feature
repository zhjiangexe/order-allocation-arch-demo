@events @cancellation @business-flow
Feature: Events 模式在倉內作業前取消 Shipment

  Background:
    * url baseUrl
    * configure retry = { count: 90, interval: 500 }
    * def buildOrderRequest = read('support/build-order-request.js')

  Scenario: Shipment 已建立但尚未開始倉內作業時，先取消 Shipment 再取消 Order
    # 此 feature 由 runner 以較長 WMS simulation delay 執行，刻意留下可取消的 CREATED 窗口。
    * def externalOrderNo = 'KARATE-EVT-CANCEL-SHIP-' + newId()
    * def order = buildOrderRequest({ ownerId: ownerId, facilityId: facilityId, externalOrderNo: externalOrderNo, lines: [{ skuCode: 'E2E-EVT-CANCEL-SHIP', quantity: 3 }] })
    Given path 'orders'
    And request order
    When method post
    Then status 200
    * def orderId = response.orderId

    # simulation delay 讓 CREATED 成為可觀察的穩定窗口，取消必須在倉內作業前送達。
    Given path 'demo', 'orders', orderId, 'fulfillment'
    And retry until response.shipments.length == 1 && response.shipments[0].status == 'CREATED'
    When method get
    Then status 200
    And match response.order.status == 'ALLOCATED'
    And match response.allocation.status == 'ALLOCATED'

    # 這時送出取消，流程必須先撤銷 Shipment，再回補 allocation 與 Order。
    * def cancellation = { requestId: '#(newId())', requestedAt: '#(now())', reason: '倉內作業開始前取消' }
    Given path 'orders', orderId, 'cancellation-requests'
    And request cancellation
    When method post
    Then status 202
    And match response.status == 'ACCEPTED'

    # 202 只代表已受理；輪詢直到三個 Context 都完成 compensation。
    Given path 'demo', 'orders', orderId, 'fulfillment'
    And retry until response.order.status == 'CANCELLED' && response.allocation.status == 'CANCELLED' && response.shipments[0].status == 'CANCELLED'
    When method get
    Then status 200
    And match response.shipments[0].cancellationOutcome == 'CANCELLED'
    And match response.shipments[0].cancellationRequestId == cancellation.requestId
    And match response.workflow == null
