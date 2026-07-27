package com.flowzati.archone.catalog.entrypoint.rest;

import com.flowzati.archone.catalog.domain.model.Product;
import com.flowzati.archone.catalog.domain.model.TemperatureZone;
import java.util.UUID;

/**
 * 商品的款。{@code ownerId} 一併回傳，因為 {@code productCode} 單獨不指向任何東西——
 * 呼叫端要用它組出後續請求時，必須同時知道它屬於誰。
 */
public record ProductResponse(
    UUID productId,
    UUID ownerId,
    String productCode,
    String name,
    TemperatureZone temperatureZone
) {

  static ProductResponse from(Product product) {
    return new ProductResponse(
        product.getId(),
        product.getOwnerId(),
        product.getProductCode(),
        product.getName(),
        product.getTemperatureZone());
  }
}
