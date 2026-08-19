package com.flowzati.archone.catalog.infrastructure.mapper;

import com.flowzati.archone.catalog.domain.aggregate.Sku;
import com.flowzati.archone.catalog.infrastructure.entity.SkuEntity;

public final class SkuMapper {

    private SkuMapper() {}

    public static SkuEntity toEntity(Sku sku) {
        return new SkuEntity(
                sku.getId(),
                sku.getOwnerId(),
                sku.getSkuCode(),
                sku.getProductCode(),
                sku.getSpecName(),
                sku.getWeightGram());
    }

    public static Sku toDomain(SkuEntity entity) {
        return new Sku(
                entity.getId(),
                entity.getOwnerId(),
                entity.getSkuCode(),
                entity.getProductCode(),
                entity.getSpecName(),
                entity.getWeightGram());
    }
}
