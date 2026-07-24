package com.flowzati.archone.allocation.domain.model;

import java.time.Instant;
import java.util.UUID;

public class StockReservation {

  private final UUID id;
  private final UUID orderId;
  private final UUID stockPoolId;
  private final int quantity;
  private ReservationStatus status;
  private final Instant reservedAt;
  private Instant releasedAt;
  private final Long version;

  private StockReservation(
      UUID id,
      UUID orderId,
      UUID stockPoolId,
      int quantity,
      ReservationStatus status,
      Instant reservedAt,
      Instant releasedAt,
      Long version
  ) {
    validateIdentity(id, orderId, stockPoolId);
    validateQuantity(quantity);
    validateState(status, reservedAt, releasedAt);
    this.id = id;
    this.orderId = orderId;
    this.stockPoolId = stockPoolId;
    this.quantity = quantity;
    this.status = status;
    this.reservedAt = reservedAt;
    this.releasedAt = releasedAt;
    this.version = version;
  }

  public static StockReservation create(
      UUID id,
      UUID orderId,
      UUID stockPoolId,
      int quantity,
      Instant reservedAt
  ) {
    return new StockReservation(
        id,
        orderId,
        stockPoolId,
        quantity,
        ReservationStatus.ACTIVE,
        reservedAt,
        null,
        null
    );
  }

  public static StockReservation rehydrate(
      UUID id,
      UUID orderId,
      UUID stockPoolId,
      int quantity,
      ReservationStatus status,
      Instant reservedAt,
      Instant releasedAt,
      Long version
  ) {
    return new StockReservation(
        id,
        orderId,
        stockPoolId,
        quantity,
        status,
        reservedAt,
        releasedAt,
        version
    );
  }

  public boolean release(Instant releasedAt) {
    requireReleasedAt(releasedAt);
    if (status == ReservationStatus.RELEASED) {
      return false;
    }
    if (releasedAt.isBefore(reservedAt)) {
      throw new IllegalArgumentException("Released time cannot be before reserved time");
    }

    status = ReservationStatus.RELEASED;
    this.releasedAt = releasedAt;
    return true;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrderId() {
    return orderId;
  }

  public UUID getStockPoolId() {
    return stockPoolId;
  }

  public int getQuantity() {
    return quantity;
  }

  public ReservationStatus getStatus() {
    return status;
  }

  public Instant getReservedAt() {
    return reservedAt;
  }

  public Instant getReleasedAt() {
    return releasedAt;
  }

  public Long getVersion() {
    return version;
  }

  private static void validateIdentity(UUID id, UUID orderId, UUID stockPoolId) {
    if (id == null) {
      throw new IllegalArgumentException("Reservation ID is required");
    }
    if (orderId == null) {
      throw new IllegalArgumentException("Order ID is required");
    }
    if (stockPoolId == null) {
      throw new IllegalArgumentException("Stock pool ID is required");
    }
  }

  private static void validateQuantity(int quantity) {
    if (quantity <= 0) {
      throw new IllegalArgumentException("Reservation quantity must be positive");
    }
  }

  private static void validateState(
      ReservationStatus status,
      Instant reservedAt,
      Instant releasedAt
  ) {
    if (status == null) {
      throw new IllegalArgumentException("Reservation status is required");
    }
    if (reservedAt == null) {
      throw new IllegalArgumentException("Reserved time is required");
    }
    if (status == ReservationStatus.ACTIVE && releasedAt != null) {
      throw new IllegalArgumentException("Active reservation cannot have a released time");
    }
    if (status == ReservationStatus.RELEASED) {
      requireReleasedAt(releasedAt);
      if (releasedAt.isBefore(reservedAt)) {
        throw new IllegalArgumentException("Released time cannot be before reserved time");
      }
    }
  }

  private static void requireReleasedAt(Instant releasedAt) {
    if (releasedAt == null) {
      throw new IllegalArgumentException("Released time is required");
    }
  }
}
