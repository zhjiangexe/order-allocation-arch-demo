package com.flowzati.archone.catalog.infrastructure.mapper;

import com.flowzati.archone.catalog.domain.model.Product;
import com.flowzati.archone.catalog.infrastructure.entity.ProductEntity;

public final class ProductMapper {

  private ProductMapper() {
  }

  public static ProductEntity toEntity(Product product) {
    return new ProductEntity(
        product.getId(),
        product.getOwnerId(),
        product.getProductCode(),
        product.getName(),
        product.getTemperatureZone()
    );
  }

  public static Product toDomain(ProductEntity entity) {
    return new Product(
        entity.getId(),
        entity.getOwnerId(),
        entity.getProductCode(),
        entity.getName(),
        entity.getTemperatureZone()
    );
  }
}
