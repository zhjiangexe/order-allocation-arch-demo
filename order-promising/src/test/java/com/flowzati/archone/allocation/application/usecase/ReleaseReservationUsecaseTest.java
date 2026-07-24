package com.flowzati.archone.allocation.application.usecase;

import com.flowzati.archone.allocation.application.command.ReleaseReservationCommand;
import com.flowzati.archone.allocation.application.coordinator.OrderAllocationCoordinator;
import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.model.StockReservation;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.allocation.domain.repository.StockReservationRepository;
import com.flowzati.archone.common.inbox.Inbox;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReleaseReservationUsecaseTest {

  private final Instant now = Instant.parse("2026-07-24T01:00:00Z");

  @Test
  void shouldDoNothingWhenMessageWasAlreadyHandled() {
    Inbox inbox = mock(Inbox.class);
    StockReservationRepository reservationRepository = mock(StockReservationRepository.class);
    StockPoolRepository stockPoolRepository = mock(StockPoolRepository.class);
    OrderAllocationCoordinator coordinator = mock(OrderAllocationCoordinator.class);
    UUID orderId = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    when(inbox.claimIfNew(messageId)).thenReturn(false);

    usecase(inbox, reservationRepository, stockPoolRepository, coordinator)
        .handle(new ReleaseReservationCommand(orderId), messageId);

    verifyNoInteractions(reservationRepository, stockPoolRepository, coordinator);
  }

  @Test
  void shouldDoNothingWhenOrderHasNoActiveReservation() {
    Inbox inbox = mock(Inbox.class);
    StockReservationRepository reservationRepository = mock(StockReservationRepository.class);
    StockPoolRepository stockPoolRepository = mock(StockPoolRepository.class);
    OrderAllocationCoordinator coordinator = mock(OrderAllocationCoordinator.class);
    UUID orderId = UUID.randomUUID();
    when(reservationRepository.findActiveByOrderId(orderId)).thenReturn(Optional.empty());

    UUID messageId = UUID.randomUUID();
    when(inbox.claimIfNew(messageId)).thenReturn(true);
    usecase(inbox, reservationRepository, stockPoolRepository, coordinator)
        .handle(new ReleaseReservationCommand(orderId), messageId);

    verify(reservationRepository).findActiveByOrderId(orderId);
    verifyNoInteractions(stockPoolRepository, coordinator);
  }

  @Test
  void shouldReleaseActiveReservationThroughCoordinator() {
    Inbox inbox = mock(Inbox.class);
    StockReservationRepository reservationRepository = mock(StockReservationRepository.class);
    StockPoolRepository stockPoolRepository = mock(StockPoolRepository.class);
    OrderAllocationCoordinator coordinator = mock(OrderAllocationCoordinator.class);
    UUID orderId = UUID.randomUUID();
    UUID stockPoolId = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    StockReservation reservation = StockReservation.create(
        UUID.randomUUID(), orderId, stockPoolId, 3, now.minusSeconds(1));
    StockPool stockPool = new StockPool(stockPoolId, "SKU-1", 10, 3, 0L);
    when(inbox.claimIfNew(messageId)).thenReturn(true);
    when(reservationRepository.findActiveByOrderId(orderId)).thenReturn(Optional.of(reservation));
    when(stockPoolRepository.findById(stockPoolId)).thenReturn(Optional.of(stockPool));

    usecase(inbox, reservationRepository, stockPoolRepository, coordinator)
        .handle(new ReleaseReservationCommand(orderId), messageId);

    verify(coordinator).releaseReservation(reservation, stockPool, now);
  }

  @Test
  void shouldFailWhenReservationStockPoolDoesNotExist() {
    Inbox inbox = mock(Inbox.class);
    StockReservationRepository reservationRepository = mock(StockReservationRepository.class);
    StockPoolRepository stockPoolRepository = mock(StockPoolRepository.class);
    OrderAllocationCoordinator coordinator = mock(OrderAllocationCoordinator.class);
    UUID orderId = UUID.randomUUID();
    UUID stockPoolId = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    StockReservation reservation = StockReservation.create(
        UUID.randomUUID(), orderId, stockPoolId, 3, now.minusSeconds(1));
    when(inbox.claimIfNew(messageId)).thenReturn(true);
    when(reservationRepository.findActiveByOrderId(orderId)).thenReturn(Optional.of(reservation));
    when(stockPoolRepository.findById(stockPoolId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> usecase(inbox, reservationRepository, stockPoolRepository, coordinator)
        .handle(new ReleaseReservationCommand(orderId), messageId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("StockPool not found: " + stockPoolId);
    verifyNoInteractions(coordinator);
  }

  private ReleaseReservationUsecase usecase(
      Inbox inbox,
      StockReservationRepository reservationRepository,
      StockPoolRepository stockPoolRepository,
      OrderAllocationCoordinator coordinator) {
    return new ReleaseReservationUsecase(
        inbox,
        reservationRepository,
        stockPoolRepository,
        coordinator,
        Clock.fixed(now, ZoneId.of("UTC"))
    );
  }
}
