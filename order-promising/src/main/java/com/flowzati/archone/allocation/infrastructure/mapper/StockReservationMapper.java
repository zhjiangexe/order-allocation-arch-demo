package com.flowzati.archone.allocation.infrastructure.mapper;

import com.flowzati.archone.allocation.domain.model.StockReservation;
import com.flowzati.archone.allocation.infrastructure.entity.StockReservationEntity;

public final class StockReservationMapper {

  private StockReservationMapper() {
  }

  public static StockReservationEntity toEntity(StockReservation reservation) {
    return new StockReservationEntity(
        reservation.getId(),
        reservation.getOrderLineId(),
        reservation.getStockPoolId(),
        reservation.getQuantity(),
        reservation.getStatus(),
        reservation.getReservedAt(),
        reservation.getReleasedAt(),
        reservation.getVersion()
    );
  }

  public static StockReservation toDomain(StockReservationEntity entity) {
    return StockReservation.rehydrate(
        entity.getId(),
        entity.getOrderLineId(),
        entity.getStockPoolId(),
        entity.getQuantity(),
        entity.getStatus(),
        entity.getReservedAt(),
        entity.getReleasedAt(),
        entity.getVersion()
    );
  }
}
