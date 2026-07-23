package com.flowzati.archone.allocation.domain.service;

import com.flowzati.archone.allocation.domain.model.ReservationStatus;
import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.model.StockReservation;
import java.time.Instant;

public class ReservationReleaseService {

  public boolean release(
      StockReservation reservation,
      StockPool stockPool,
      Instant releasedAt
  ) {
    if (reservation == null) {
      throw new IllegalArgumentException("Reservation is required");
    }
    if (stockPool == null) {
      throw new IllegalArgumentException("Stock pool is required");
    }
    if (!reservation.getStockPoolId().equals(stockPool.getId())) {
      throw new IllegalArgumentException("Reservation does not belong to stock pool");
    }
    if (reservation.getStatus() == ReservationStatus.RELEASED) {
      return false;
    }
    if (releasedAt == null) {
      throw new IllegalArgumentException("Released time is required");
    }
    if (releasedAt.isBefore(reservation.getReservedAt())) {
      throw new IllegalArgumentException("Released time cannot be before reserved time");
    }
    if (reservation.getQuantity() > stockPool.getReservedQuantity()) {
      throw new IllegalArgumentException("Quantity to release cannot exceed reserved quantity");
    }

    reservation.release(releasedAt);
    stockPool.release(reservation.getQuantity());
    return true;
  }
}
