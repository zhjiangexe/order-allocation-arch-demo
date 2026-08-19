package com.flowzati.archone.stock.movement.infrastructure.mapper;

import com.flowzati.archone.stock.movement.domain.aggregate.StockMove;
import com.flowzati.archone.stock.movement.domain.entity.StockMoveLine;
import com.flowzati.archone.stock.movement.infrastructure.entity.StockMoveEntity;
import com.flowzati.archone.stock.movement.infrastructure.entity.StockMoveLineEntity;

/**
 * 搬運與它的明細共用一個 mapper，理由與 repository 相同：明細沒有獨立的生命週期。
 */
public final class StockMoveMapper {

  private StockMoveMapper() {
  }

  public static StockMoveEntity toEntity(StockMove move) {
    return new StockMoveEntity(
        move.getId(), move.getPickingId(), move.getOwnerId(), move.getSkuCode(),
        move.getFromLocationId(), move.getToLocationId(), move.getAllocationDemandId(),
        move.getAllocationDemandLineId(), move.getSourceLineId(), move.getOrderLineId(),
        move.getDemandQuantity(), move.getState(), move.getCreatedAt(), move.getAssignedAt(),
        move.getVersion());
  }

  public static StockMove toDomain(StockMoveEntity entity) {
    return new StockMove(
        entity.getId(), entity.getPickingId(), entity.getOwnerId(), entity.getSkuCode(),
        entity.getFromLocationId(), entity.getToLocationId(), entity.getAllocationDemandId(),
        entity.getAllocationDemandLineId(), entity.getSourceLineId(), entity.getOrderLineId(),
        entity.getDemandQuantity(), entity.getState(), entity.getCreatedAt(),
        entity.getAssignedAt(), entity.getVersion());
  }

  public static StockMoveLineEntity toEntity(StockMoveLine line) {
    return new StockMoveLineEntity(line.id(), line.moveId(), line.stockPoolId(), line.quantity());
  }

  public static StockMoveLine toDomain(StockMoveLineEntity entity) {
    return new StockMoveLine(
        entity.getId(), entity.getMoveId(), entity.getStockPoolId(), entity.getQuantity());
  }
}
