@events @messaging-resilience
Feature: Debezium connector 暫停後追趕

  Background:
    * configure retry = { count: 90, interval: 1000 }
    * def buildOrderRequest = read('support/build-order-request.js')

  Scenario: Connect 暫停期間已提交的訂單，恢復後仍會從 WAL 追上並完成
    # 先停 connector，證明 HTTP transaction 與事件傳遞解耦：訂單可提交，但暫時不會進入 allocation。
    Given url connectUrl
    And path 'connectors', connectorName, 'pause'
    When method put
    Then status 202

    Given url connectUrl
    And path 'connectors', connectorName, 'status'
    And retry until response.connector.state == 'PAUSED'
    When method get
    Then status 200

    # connector 已停止傳遞事件，此時下單只應完成 Ordering transaction。
    Given url baseUrl
    And path 'orders'
    * def externalOrderNo = 'KARATE-CONNECT-PAUSED-' + newId()
    * def order = buildOrderRequest({ ownerId: ownerId, facilityId: facilityId, externalOrderNo: externalOrderNo, lines: [{ skuCode: 'E2E-EVT-CONNECT', quantity: 2 }] })
    And request order
    When method post
    Then status 200
    And match response.orderId == '#uuid'
    And match response.status == 'PENDING'
    * def orderId = response.orderId
    * karate.pause(2000)

    # 留出正常消費時間後仍是 PENDING，確認事件確實尚未送達 allocation。
    Given url baseUrl
    And path 'orders', orderId
    When method get
    Then status 200
    And match response.status == 'PENDING'

    # 恢復 connector 後，不重送 HTTP 命令；Debezium 應自行發布先前的 outbox record。
    Given url connectUrl
    And path 'connectors', connectorName, 'resume'
    When method put
    Then status 202

    Given url connectUrl
    And path 'connectors', connectorName, 'status'
    And retry until response.connector.state == 'RUNNING' && response.tasks[0].state == 'RUNNING'
    When method get
    Then status 200

    # 不重送訂單，等待先前的 outbox record 追上並讓各 Context 收斂。
    Given url baseUrl
    And path 'demo', 'orders', orderId, 'fulfillment'
    And retry until response.order.status == 'FULFILLED'
    When method get
    Then status 200
    And match response.orchestrationMode == 'EVENTS'
    And match response.order.status == 'FULFILLED'
    And match response.allocation.status == 'ALLOCATED'
    And match response.shipments == '#[1]'
    And match response.shipments[0].status == 'HANDED_OVER_TO_CARRIER'
