package com.flowzati.archone.logisticsdata.infrastructure.persistence.jpa.mapper;

import com.flowzati.archone.logisticsdata.domain.aggregate.Sku;
import com.flowzati.archone.logisticsdata.infrastructure.persistence.jpa.model.SkuEntity;

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
