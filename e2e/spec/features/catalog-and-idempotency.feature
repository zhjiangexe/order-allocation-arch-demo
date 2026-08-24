@events @business-flow
Feature: 下單前主檔與同步命令的邊界行為

  Background:
    * url baseUrl
    * configure retry = { count: 30, interval: 500 }
    * def buildOrderRequest = read('support/build-order-request.js')
    * def buildStockReceiptRequest = read('support/build-stock-receipt-request.js')

  Scenario: 下單端可以依貨主逐層取得商品、規格、可用倉與庫位
    # 這些查詢是實際下單流程的前置步驟，不直接假設 UI 已知道所有 UUID。
    Given path 'owners'
    When method get
    Then status 200
    And match response contains deep { ownerId: '#(ownerId)', code: 'OWNER-A', name: '甲貨主' }

    # 商品與 SKU 都受 owner 邊界限制，避免拿到其他貨主的規格。
    Given path 'owners', ownerId, 'products'
    When method get
    Then status 200
    And match response contains deep { ownerId: '#(ownerId)', productCode: 'P-TEA', temperatureZone: 'AMBIENT' }

    Given path 'owners', ownerId, 'products', 'P-TEA', 'skus'
    When method get
    Then status 200
    And match response contains deep { ownerId: '#(ownerId)', skuCode: 'E2E-EVT-HAPPY', productCode: 'P-TEA' }

    # 可用倉與庫位決定訂單能選擇的實際履約位置。
    Given path 'owners', ownerId, 'facilities'
    When method get
    Then status 200
    And match response contains deep { facilityId: '#(facilityId)', code: 'WH-NORTH', name: '北部倉' }

    Given path 'facilities', facilityId, 'locations'
    When method get
    Then status 200
    And match response contains deep { locationId: '#(locationId)', code: 'WH-NORTH/Stock' }

  Scenario: 相同貨主與上游單號不能建立第二張實體訂單
    # 目前訂單 API 對重送明確回 409；這能防止同一張單出貨兩次，但尚不是「回傳原訂單」式冪等。
    * def externalOrderNo = 'KARATE-DUPLICATE-' + newId()
    * def order = buildOrderRequest({ ownerId: ownerId, facilityId: facilityId, externalOrderNo: externalOrderNo, lines: [{ skuCode: 'E2E-ORDER-DUPLICATE', quantity: 1 }] })
    Given path 'orders'
    And request order
    When method post
    Then status 200
    And match response.orderId == '#uuid'

    # 完全相同的命令重送，也不能建立第二張訂單。
    Given path 'orders'
    And request order
    When method post
    Then status 409
    And match response contains 'already exists'

  Scenario: 收貨以 receiptId 保證重試不會重複入帳，內容不同則拒絕
    # 第一次與完全相同的 retry 都成功；第二次不能讓庫存從 5 變成 10。
    * def sku = 'E2E-RECEIPT-IDEMPOTENCY'
    * def receiptId = newId()
    * def receipt = buildStockReceiptRequest({ receiptId: receiptId, ownerId: ownerId, facilityId: facilityId, locationId: locationId, sku: sku, quantity: 5 })
    Given path 'stock-receipts'
    And request receipt
    When method post
    Then status 200
    And match response == { receiptId: '#(receiptId)', sku: '#(sku)', quantity: 5 }

    # 模擬上游未收到 response 後，以相同 receiptId 重送相同內容。
    Given path 'stock-receipts'
    And request receipt
    When method post
    Then status 200

    # 從庫存 read model 確認 retry 沒有造成重複入帳。
    Given path 'stock-pool'
    And param ownerId = ownerId
    And param locationId = locationId
    When method get
    Then status 200
    * def skuStock = karate.filter(response.skus, function(x){ return x.sku == sku })[0]
    * def receiptBatch = karate.filter(skuStock.batches, function(x){ return x.inDate == '2026-08-24' })[0]
    And match receiptBatch.onHandQuantity == 5
    And match receiptBatch.reservedQuantity == 0

    # 同一個冪等鍵不能代表另一份業務內容，否則 retry 會變成改單。
    * def conflictingReceipt = buildStockReceiptRequest({ receiptId: receiptId, ownerId: ownerId, facilityId: facilityId, locationId: locationId, sku: sku, quantity: 6 })
    Given path 'stock-receipts'
    And request conflictingReceipt
    When method post
    Then status 409
    And match response contains 'already bound'
