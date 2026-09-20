package com.flowzati.archone.inventory.movement.infrastructure.persistence.jpa.mapper;

import com.flowzati.archone.inventory.movement.domain.aggregate.StockOperation;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationDirection;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationSource;
import com.flowzati.archone.inventory.movement.infrastructure.persistence.jpa.entity.StockOperationEntity;

public final class StockOperationMapper {

    private StockOperationMapper() {}

    public static StockOperationEntity toEntity(StockOperation operation) {
        return new StockOperationEntity(
                operation.id(),
                operation.stockOperationTypeId(),
                operation.direction(),
                operation.ownerId(),
                operation.source() == null ? null : operation.source().sourceType(),
                operation.source() == null ? null : operation.source().sourceId(),
                operation.source() == null ? null : operation.source().allocationUnitKey(),
                operation.assignmentPolicy(),
                operation.enqueuedAt(),
                operation.fromLocationId(),
                operation.toLocationId(),
                operation.dispatchBy(),
                operation.releasePriority(),
                operation.state(),
                operation.version());
    }

    public static StockOperation toDomain(StockOperationEntity entity) {
        return new StockOperation(
                entity.getId(),
                entity.getStockOperationTypeId(),
                entity.getDirection() == null
                        ? (entity.getDispatchBy() == null
                                ? StockOperationDirection.INBOUND
                                : StockOperationDirection.OUTBOUND)
                        : entity.getDirection(),
                entity.getOwnerId(),
                entity.getFromLocationId(),
                entity.getToLocationId(),
                entity.getSourceType() == null
                        ? null
                        : new StockOperationSource(
                                entity.getSourceType(), entity.getSourceId(), entity.getAllocationUnitKey()),
                entity.getAssignmentPolicy(),
                entity.getEnqueuedAt(),
                entity.getDispatchBy(),
                entity.getReleasePriority(),
                entity.getState(),
                entity.getVersion());
    }
}
