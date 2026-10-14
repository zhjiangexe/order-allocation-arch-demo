@ignore
Feature: 驗證單批測試 SKU 的庫存餘額

  Scenario:
    Given url inventoryUrl
    And path 'stock-pool'
    And param ownerId = ownerId
    And param locationId = locationId
    And retry until responseStatus == 200 && response.skus.some(x => x.sku == sku && x.batches.length == 1 && x.batches[0].onHandQuantity == onHand && x.batches[0].reservedQuantity == reserved && x.batches[0].availableToPromise == available)
    When method get
    Then status 200
    * def skuStocks = karate.filter(response.skus, x => x.sku == sku)
    And match skuStocks == '#[1]'
    And match skuStocks[0].batches == '#[1]'
    And match skuStocks[0].batches[0] contains { onHandQuantity: '#(onHand)', reservedQuantity: '#(reserved)', availableToPromise: '#(available)', expired: false }
