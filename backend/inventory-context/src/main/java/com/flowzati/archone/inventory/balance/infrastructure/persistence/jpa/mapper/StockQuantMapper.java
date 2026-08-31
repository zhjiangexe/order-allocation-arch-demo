package com.flowzati.archone.inventory.balance.infrastructure.persistence.jpa.mapper;

import com.flowzati.archone.inventory.balance.domain.aggregate.StockQuant;
import com.flowzati.archone.inventory.balance.infrastructure.persistence.jpa.entity.StockQuantEntity;

public final class StockQuantMapper {

    private StockQuantMapper() {}

    public static StockQuantEntity toEntity(StockQuant stockQuant) {
        return new StockQuantEntity(
                stockQuant.getId(),
                stockQuant.getOwnerId(),
                stockQuant.getLocationId(),
                stockQuant.getSkuCode(),
                stockQuant.getInDate(),
                stockQuant.getExpiryDate(),
                stockQuant.getOnHandQuantity(),
                stockQuant.getReservedQuantity(),
                stockQuant.getVersion());
    }

    public static StockQuant toDomain(StockQuantEntity entity) {
        return new StockQuant(
                entity.getId(),
                entity.getOwnerId(),
                entity.getLocationId(),
                entity.getSkuCode(),
                entity.getInDate(),
                entity.getExpiryDate(),
                entity.getOnHandQuantity(),
                entity.getReservedQuantity(),
                entity.getVersion());
    }
}
