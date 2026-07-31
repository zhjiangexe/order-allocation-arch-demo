package com.flowzati.archone.allocation.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("StockReservation 領域模型")
class StockReservationTest {

  private static final Instant RESERVED_AT = Instant.parse("2026-07-23T08:00:00Z");
  private static final UUID STOCK_POOL_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");

  @Test
  @DisplayName("建立 reservation 時應為 ACTIVE 並保留建立資料")
  void createsActiveReservation() {
    UUID id = UUID.randomUUID();
    UUID orderLineId = UUID.randomUUID();

    StockReservation reservation = StockReservation.create(
        id,
        UUID.randomUUID(),
        orderLineId,
        STOCK_POOL_ID,
        3,
        RESERVED_AT
    );

    assertThat(reservation.getId()).isEqualTo(id);
    assertThat(reservation.getOrderLineId()).isEqualTo(orderLineId);
    assertThat(reservation.getStockPoolId()).isEqualTo(STOCK_POOL_ID);
    assertThat(reservation.getQuantity()).isEqualTo(3);
    assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.ACTIVE);
    assertThat(reservation.getReservedAt()).isEqualTo(RESERVED_AT);
    assertThat(reservation.getReleasedAt()).isNull();
    assertThat(reservation.getVersion()).isNull();
  }

  @Test
  @DisplayName("釋放 ACTIVE reservation 時應轉為 RELEASED 並記錄時間")
  void releasesActiveReservation() {
    StockReservation reservation = activeReservation();
    Instant releasedAt = RESERVED_AT.plusSeconds(60);

    boolean released = reservation.release(releasedAt);

    assertThat(released).isTrue();
    assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RELEASED);
    assertThat(reservation.getReleasedAt()).isEqualTo(releasedAt);
  }

  @Test
  @DisplayName("重複釋放 RELEASED reservation 應為 no-op 並保留原釋放時間")
  void repeatedReleaseIsNoOp() {
    StockReservation reservation = activeReservation();
    Instant firstReleasedAt = RESERVED_AT.plusSeconds(60);
    reservation.release(firstReleasedAt);

    boolean releasedAgain = reservation.release(RESERVED_AT.plusSeconds(120));

    assertThat(releasedAgain).isFalse();
    assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RELEASED);
    assertThat(reservation.getReleasedAt()).isEqualTo(firstReleasedAt);
  }

  @Test
  @DisplayName("釋放時間早於保留時間時應拒絕轉換並保持 ACTIVE")
  void rejectsReleaseBeforeReservedTime() {
    StockReservation reservation = activeReservation();

    assertThatThrownBy(() -> reservation.release(RESERVED_AT.minusSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Released time cannot be before reserved time");

    assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.ACTIVE);
    assertThat(reservation.getReleasedAt()).isNull();
  }

  @Test
  @DisplayName("釋放時間不得為 null")
  void rejectsNullReleasedTime() {
    StockReservation reservation = activeReservation();

    assertThatThrownBy(() -> reservation.release(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Released time is required");

    assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.ACTIVE);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  @DisplayName("建立 reservation 時應拒絕非正數 quantity")
  void rejectsNonPositiveQuantity(int quantity) {
    assertThatThrownBy(
        () -> StockReservation.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            quantity,
            RESERVED_AT
        )
    ).isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Reservation quantity must be positive");
  }

  @Test
  @DisplayName("建立 reservation 時應拒絕缺少 identity 或保留時間")
  void rejectsMissingRequiredCreationData() {
    UUID id = UUID.randomUUID();
    UUID orderLineId = UUID.randomUUID();

    assertThatThrownBy(() -> StockReservation.create(null, UUID.randomUUID(), orderLineId, UUID.randomUUID(), 1, RESERVED_AT))
        .hasMessage("Reservation ID is required");
    assertThatThrownBy(() -> StockReservation.create(id, UUID.randomUUID(), null, UUID.randomUUID(), 1, RESERVED_AT))
        .hasMessage("Order line ID is required");
    assertThatThrownBy(() -> StockReservation.create(id, UUID.randomUUID(), orderLineId, null, 1, RESERVED_AT))
        .hasMessage("Stock pool ID is required");
    assertThatThrownBy(() -> StockReservation.create(id, UUID.randomUUID(), orderLineId, UUID.randomUUID(), 1, null))
        .hasMessage("Reserved time is required");
  }

  @Test
  @DisplayName("還原 ACTIVE reservation 時不得帶有 releasedAt")
  void rejectsActiveStateWithReleasedTime() {
    assertThatThrownBy(
        () -> StockReservation.rehydrate(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            ReservationStatus.ACTIVE,
            RESERVED_AT,
            RESERVED_AT.plusSeconds(60),
            0L
        )
    ).isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Active reservation cannot have a released time");
  }

  @Test
  @DisplayName("還原 RELEASED reservation 時必須帶有合法 releasedAt")
  void rejectsReleasedStateWithoutValidReleasedTime() {
    assertThatThrownBy(
        () -> StockReservation.rehydrate(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            ReservationStatus.RELEASED,
            RESERVED_AT,
            null,
            0L
        )
    ).isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Released time is required");

    assertThatThrownBy(
        () -> StockReservation.rehydrate(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            ReservationStatus.RELEASED,
            RESERVED_AT,
            RESERVED_AT.minusSeconds(1),
            0L
        )
    ).isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Released time cannot be before reserved time");
  }

  @Test
  @DisplayName("應能還原完整的 RELEASED reservation")
  void rehydratesReleasedReservation() {
    UUID id = UUID.randomUUID();
    UUID orderLineId = UUID.randomUUID();
    Instant releasedAt = RESERVED_AT.plusSeconds(60);

    StockReservation reservation = StockReservation.rehydrate(
        id,
        UUID.randomUUID(),
        orderLineId,
        STOCK_POOL_ID,
        3,
        ReservationStatus.RELEASED,
        RESERVED_AT,
        releasedAt,
        7L
    );

    assertThat(reservation.getId()).isEqualTo(id);
    assertThat(reservation.getOrderLineId()).isEqualTo(orderLineId);
    assertThat(reservation.getStockPoolId()).isEqualTo(STOCK_POOL_ID);
    assertThat(reservation.getQuantity()).isEqualTo(3);
    assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RELEASED);
    assertThat(reservation.getReservedAt()).isEqualTo(RESERVED_AT);
    assertThat(reservation.getReleasedAt()).isEqualTo(releasedAt);
    assertThat(reservation.getVersion()).isEqualTo(7L);
  }

  private StockReservation activeReservation() {
    return StockReservation.create(
        STOCK_POOL_ID,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        3,
        RESERVED_AT
    );
  }
}
