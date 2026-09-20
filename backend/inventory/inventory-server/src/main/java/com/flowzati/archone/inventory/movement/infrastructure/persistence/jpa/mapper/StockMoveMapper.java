package com.flowzati.archone.inventory.movement.infrastructure.persistence.jpa.mapper;

import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import com.flowzati.archone.inventory.movement.infrastructure.persistence.jpa.entity.StockMoveEntity;

/** Canonical Stock Move persistence mapping. */
public final class StockMoveMapper {

    private StockMoveMapper() {}

    public static StockMoveEntity toEntity(StockMove move) {
        return new StockMoveEntity(
                move.getId(),
                move.getStockOperationId(),
                move.getOwnerId(),
                move.getSkuCode(),
                move.getFromLocationId(),
                move.getToLocationId(),
                move.getSourceLineId(),
                move.getLineSequence(),
                move.getDemandQuantity(),
                move.getState(),
                move.getCreatedAt(),
                move.getAssignedAt(),
                move.getVersion());
    }

    public static StockMove toDomain(StockMoveEntity entity) {
        return new StockMove(
                entity.getId(),
                entity.getStockOperationId(),
                entity.getOwnerId(),
                entity.getSkuCode(),
                entity.getFromLocationId(),
                entity.getToLocationId(),
                entity.getSourceLineId(),
                entity.getLineSequence(),
                entity.getDemandQuantity(),
                entity.getState(),
                entity.getCreatedAt(),
                entity.getAssignedAt(),
                entity.getVersion());
    }
}
