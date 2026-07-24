package com.flowzati.archone.allocation.application.usecase;

import com.flowzati.archone.allocation.application.command.ReleaseReservationCommand;
import com.flowzati.archone.allocation.application.coordinator.OrderAllocationCoordinator;
import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.model.StockReservation;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.allocation.domain.repository.StockReservationRepository;
import com.flowzati.archone.common.inbox.InboxRepo;
import com.flowzati.archone.common.inbox.InboundCommand;
import com.flowzati.archone.common.inbox.MessageMetadata;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReleaseReservationUsecaseTest {

  private final Instant now = Instant.parse("2026-07-24T01:00:00Z");

  @Test
  @DisplayName("訊息已處理過時不應再執行釋放")
  void shouldDoNothingWhenMessageWasAlreadyHandled() {
    InboxRepo inboxRepo = mock(InboxRepo.class);
    StockReservationRepository reservationRepository = mock(StockReservationRepository.class);
    StockPoolRepository stockPoolRepository = mock(StockPoolRepository.class);
    OrderAllocationCoordinator coordinator = mock(OrderAllocationCoordinator.class);
    UUID orderId = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    when(inboxRepo.claimIfNew(message(messageId))).thenReturn(false);

    usecase(inboxRepo, reservationRepository, stockPoolRepository, coordinator)
        .handle(inbound(orderId, messageId));

    verifyNoInteractions(reservationRepository, stockPoolRepository, coordinator);
  }

  @Test
  @DisplayName("訂單沒有有效 Reservation 時應為合法 no-op")
  void shouldDoNothingWhenOrderHasNoActiveReservation() {
    InboxRepo inboxRepo = mock(InboxRepo.class);
    StockReservationRepository reservationRepository = mock(StockReservationRepository.class);
    StockPoolRepository stockPoolRepository = mock(StockPoolRepository.class);
    OrderAllocationCoordinator coordinator = mock(OrderAllocationCoordinator.class);
    UUID orderId = UUID.randomUUID();
    when(reservationRepository.findActiveByOrderId(orderId)).thenReturn(Optional.empty());

    UUID messageId = UUID.randomUUID();
    when(inboxRepo.claimIfNew(message(messageId))).thenReturn(true);
    usecase(inboxRepo, reservationRepository, stockPoolRepository, coordinator)
        .handle(inbound(orderId, messageId));

    verify(reservationRepository).findActiveByOrderId(orderId);
    verifyNoInteractions(stockPoolRepository, coordinator);
  }

  @Test
  @DisplayName("有效 Reservation 應透過 Coordinator 釋放")
  void shouldReleaseActiveReservationThroughCoordinator() {
    InboxRepo inboxRepo = mock(InboxRepo.class);
    StockReservationRepository reservationRepository = mock(StockReservationRepository.class);
    StockPoolRepository stockPoolRepository = mock(StockPoolRepository.class);
    OrderAllocationCoordinator coordinator = mock(OrderAllocationCoordinator.class);
    UUID orderId = UUID.randomUUID();
    UUID stockPoolId = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    StockReservation reservation = StockReservation.create(
        UUID.randomUUID(), orderId, stockPoolId, 3, now.minusSeconds(1));
    StockPool stockPool = new StockPool(stockPoolId, "SKU-1", 10, 3, 0L);
    when(inboxRepo.claimIfNew(message(messageId))).thenReturn(true);
    when(reservationRepository.findActiveByOrderId(orderId)).thenReturn(Optional.of(reservation));
    when(stockPoolRepository.findById(stockPoolId)).thenReturn(Optional.of(stockPool));

    usecase(inboxRepo, reservationRepository, stockPoolRepository, coordinator)
        .handle(inbound(orderId, messageId));

    verify(coordinator).releaseReservation(reservation, stockPool, now);
  }

  @Test
  @DisplayName("Reservation 對應的 StockPool 不存在時應失敗")
  void shouldFailWhenReservationStockPoolDoesNotExist() {
    InboxRepo inboxRepo = mock(InboxRepo.class);
    StockReservationRepository reservationRepository = mock(StockReservationRepository.class);
    StockPoolRepository stockPoolRepository = mock(StockPoolRepository.class);
    OrderAllocationCoordinator coordinator = mock(OrderAllocationCoordinator.class);
    UUID orderId = UUID.randomUUID();
    UUID stockPoolId = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    StockReservation reservation = StockReservation.create(
        UUID.randomUUID(), orderId, stockPoolId, 3, now.minusSeconds(1));
    when(inboxRepo.claimIfNew(message(messageId))).thenReturn(true);
    when(reservationRepository.findActiveByOrderId(orderId)).thenReturn(Optional.of(reservation));
    when(stockPoolRepository.findById(stockPoolId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> usecase(inboxRepo, reservationRepository, stockPoolRepository, coordinator)
        .handle(inbound(orderId, messageId)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("StockPool not found: " + stockPoolId);
    verifyNoInteractions(coordinator);
  }

  private ReleaseReservationUsecase usecase(
      InboxRepo inboxRepo,
      StockReservationRepository reservationRepository,
      StockPoolRepository stockPoolRepository,
      OrderAllocationCoordinator coordinator) {
    return new ReleaseReservationUsecase(
        inboxRepo,
        reservationRepository,
        stockPoolRepository,
        coordinator,
        Clock.fixed(now, ZoneId.of("UTC"))
    );
  }

  private MessageMetadata message(UUID eventId) {
    return new MessageMetadata(eventId, "OrderCancelledIntegrationEvent");
  }

  private InboundCommand<ReleaseReservationCommand> inbound(UUID orderId, UUID eventId) {
    return new InboundCommand<>(new ReleaseReservationCommand(orderId), message(eventId));
  }
}
