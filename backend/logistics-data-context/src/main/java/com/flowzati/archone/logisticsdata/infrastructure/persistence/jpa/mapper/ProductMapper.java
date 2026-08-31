package com.flowzati.archone.logisticsdata.infrastructure.persistence.jpa.mapper;

import com.flowzati.archone.logisticsdata.domain.aggregate.Product;
import com.flowzati.archone.logisticsdata.infrastructure.persistence.jpa.model.ProductEntity;

public final class ProductMapper {

    private ProductMapper() {}

    public static ProductEntity toEntity(Product product) {
        return new ProductEntity(
                product.getId(),
                product.getOwnerId(),
                product.getProductCode(),
                product.getName(),
                product.getTemperatureZone());
    }

    public static Product toDomain(ProductEntity entity) {
        return new Product(
                entity.getId(),
                entity.getOwnerId(),
                entity.getProductCode(),
                entity.getName(),
                entity.getTemperatureZone());
    }
}
