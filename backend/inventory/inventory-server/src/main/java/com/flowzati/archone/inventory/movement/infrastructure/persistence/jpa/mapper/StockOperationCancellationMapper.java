package com.flowzati.archone.inventory.movement.infrastructure.persistence.jpa.mapper;

import com.flowzati.archone.inventory.movement.domain.aggregate.StockOperationCancellation;
import com.flowzati.archone.inventory.movement.infrastructure.persistence.jpa.entity.StockOperationCancellationEntity;

public final class StockOperationCancellationMapper {

    private StockOperationCancellationMapper() {}

    public static StockOperationCancellationEntity toEntity(StockOperationCancellation operation) {
        return new StockOperationCancellationEntity(
                operation.stockOperationId(),
                operation.cancellationOperationId(),
                operation.state(),
                operation.startedAt(),
                operation.updatedAt(),
                operation.version());
    }

    public static StockOperationCancellation toDomain(StockOperationCancellationEntity entity) {
        return StockOperationCancellation.rehydrate(
                entity.getStockOperationId(),
                entity.getCancellationOperationId(),
                entity.getStartedAt(),
                entity.getUpdatedAt(),
                entity.getState(),
                entity.getVersion());
    }
}
