package com.flowzati.archone.inventory.reservation.infrastructure.mapper;

import com.flowzati.archone.inventory.reservation.domain.StockMoveLine;
import com.flowzati.archone.inventory.reservation.infrastructure.entity.StockMoveLineEntity;

public final class StockMoveLineMapper {

    private StockMoveLineMapper() {}

    public static StockMoveLineEntity toEntity(StockMoveLine stockMoveLine) {
        return new StockMoveLineEntity(
                stockMoveLine.id(), stockMoveLine.moveId(), stockMoveLine.stockQuantId(), stockMoveLine.quantity());
    }

    public static StockMoveLine toDomain(StockMoveLineEntity entity) {
        return new StockMoveLine(entity.getId(), entity.getMoveId(), entity.getStockQuantId(), entity.getQuantity());
    }
}
