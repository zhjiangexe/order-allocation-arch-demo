package com.flowzati.archone.allocation.infrastructure.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.allocation.domain.model.ReservationStatus;
import com.flowzati.archone.allocation.domain.model.StockReservation;
import com.flowzati.archone.allocation.infrastructure.entity.StockReservationEntity;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("StockReservation persistence mapper")
class StockReservationMapperTest {

  private static final Instant RESERVED_AT = Instant.parse("2026-07-24T08:00:00Z");

  @Test
  @DisplayName("應將完整 RELEASED reservation 映射至 entity")
  void mapsDomainToEntity() {
    UUID reservationId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    Instant releasedAt = RESERVED_AT.plusSeconds(60);
    StockReservation reservation = StockReservation.rehydrate(
        reservationId,
        orderId,
        10L,
        3,
        ReservationStatus.RELEASED,
        RESERVED_AT,
        releasedAt,
        7L
    );

    StockReservationEntity entity = StockReservationMapper.toEntity(reservation);

    assertThat(entity.getId()).isEqualTo(reservationId);
    assertThat(entity.getOrderId()).isEqualTo(orderId);
    assertThat(entity.getStockPoolId()).isEqualTo(10L);
    assertThat(entity.getQuantity()).isEqualTo(3);
    assertThat(entity.getStatus()).isEqualTo(ReservationStatus.RELEASED);
    assertThat(entity.getReservedAt()).isEqualTo(RESERVED_AT);
    assertThat(entity.getReleasedAt()).isEqualTo(releasedAt);
    assertThat(entity.getVersion()).isEqualTo(7L);
  }

  @Test
  @DisplayName("應由 entity 還原完整 ACTIVE reservation")
  void mapsEntityToDomain() {
    UUID reservationId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    StockReservationEntity entity = new StockReservationEntity(
        reservationId,
        orderId,
        10L,
        3,
        ReservationStatus.ACTIVE,
        RESERVED_AT,
        null,
        7L
    );

    StockReservation reservation = StockReservationMapper.toDomain(entity);

    assertThat(reservation.getId()).isEqualTo(reservationId);
    assertThat(reservation.getOrderId()).isEqualTo(orderId);
    assertThat(reservation.getStockPoolId()).isEqualTo(10L);
    assertThat(reservation.getQuantity()).isEqualTo(3);
    assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.ACTIVE);
    assertThat(reservation.getReservedAt()).isEqualTo(RESERVED_AT);
    assertThat(reservation.getReleasedAt()).isNull();
    assertThat(reservation.getVersion()).isEqualTo(7L);
  }
}
