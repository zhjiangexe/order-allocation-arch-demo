package com.flowzati.archone.allocation.application.usecase;

import com.flowzati.archone.allocation.application.command.ReleaseReservationCommand;
import com.flowzati.archone.allocation.application.coordinator.OrderAllocationCoordinator;
import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.model.StockFixtures;
import com.flowzati.archone.allocation.domain.model.StockReservation;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.allocation.domain.repository.StockReservationRepository;
import com.flowzati.archone.common.inbox.InboxRepo;
import com.flowzati.archone.common.inbox.InboundCommand;
import com.flowzati.archone.common.inbox.MessageMetadata;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import com.flowzati.archone.testsupport.OrderFixtures;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReleaseReservationUsecaseTest {

  private final Instant now = Instant.parse("2026-07-24T01:00:00Z");

  private InboxRepo inboxRepo;
  private StockReservationRepository reservationRepository;
  private StockPoolRepository stockPoolRepository;
  private OrderAllocationCoordinator coordinator;
  private ReleaseReservationUsecase usecase;

  @BeforeEach
  void setUp() {
    inboxRepo = mock(InboxRepo.class);
    reservationRepository = mock(StockReservationRepository.class);
    stockPoolRepository = mock(StockPoolRepository.class);
    coordinator = mock(OrderAllocationCoordinator.class);
    usecase = new ReleaseReservationUsecase(
        inboxRepo,
        reservationRepository,
        stockPoolRepository,
        coordinator,
        Clock.fixed(now, ZoneId.of("UTC"))
    );
  }

  @Test
  @DisplayName("訊息已處理過時不應再執行釋放")
  void shouldDoNothingWhenMessageWasAlreadyHandled() {
    UUID messageId = UUID.randomUUID();
    when(inboxRepo.claimIfNew(message(messageId))).thenReturn(false);

    usecase.handle(inbound(UUID.randomUUID(), messageId));

    verifyNoInteractions(
        reservationRepository, stockPoolRepository, coordinator);
  }

  @Test
  @DisplayName("訂單沒有有效 Reservation 時應為合法 no-op")
  void shouldDoNothingWhenOrderHasNoActiveReservation() {
    UUID messageId = UUID.randomUUID();
    Order order = allocatedOrder();
    when(inboxRepo.claimIfNew(message(messageId))).thenReturn(true);
    when(reservationRepository.findActiveByOrderId(order.getId())).thenReturn(List.of());

    usecase.handle(inbound(order.getId(), messageId));

    verify(reservationRepository).findActiveByOrderId(order.getId());
    verifyNoInteractions(stockPoolRepository, coordinator);
  }

  @Test
  @DisplayName("這張單沒有有效預留時應為合法 no-op——沒有東西要釋放")
  void shouldDoNothingWhenTheOrderHoldsNoReservation() {
    UUID messageId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    when(inboxRepo.claimIfNew(message(messageId))).thenReturn(true);
    // 不再先查訂單存不存在——預留是 allocation 自己的資料，直接問它就好。訂單不存在、
    // 訂單存在但沒配到、預留已經釋放過，三種情形在這裡是同一件事：沒有東西要釋放。
    when(reservationRepository.findActiveByOrderId(orderId)).thenReturn(List.of());

    usecase.handle(inbound(orderId, messageId));

    verify(reservationRepository).findActiveByOrderId(orderId);
    verifyNoInteractions(stockPoolRepository, coordinator);
  }

  @Test
  @DisplayName("一條行跨兩批時應把兩筆預留一起交給 Coordinator 釋放")
  void shouldReleaseEveryActiveReservationOfTheOrder() {
    UUID messageId = UUID.randomUUID();
    Order order = allocatedOrder();
    UUID lineId = order.getLines().get(0).getId();
    StockPool near = StockFixtures.unexpiredBatch("SKU-1", 60, 60);
    StockPool far = StockFixtures.unexpiredBatch("SKU-1", 40, 20);
    StockReservation onNear = StockReservation.create(
        UUID.randomUUID(),
        UUID.randomUUID(), lineId, near.getId(), 60, now.minusSeconds(1));
    StockReservation onFar = StockReservation.create(
        UUID.randomUUID(),
        UUID.randomUUID(), lineId, far.getId(), 20, now.minusSeconds(1));
    when(inboxRepo.claimIfNew(message(messageId))).thenReturn(true);
    when(reservationRepository.findActiveByOrderId(order.getId()))
        .thenReturn(List.of(onNear, onFar));
    when(stockPoolRepository.findById(near.getId())).thenReturn(Optional.of(near));
    when(stockPoolRepository.findById(far.getId())).thenReturn(Optional.of(far));

    usecase.handle(inbound(order.getId(), messageId));

    // 只釋放第一筆的話，遠效期那 20 件會永遠鎖著，而且不會有任何錯誤浮現。
    verify(coordinator).releaseReservations(
        eq(List.of(onNear, onFar)),
        eq(Map.of(near.getId(), near, far.getId(), far)),
        eq(now));
  }

  @Test
  @DisplayName("Reservation 對應的批不存在時應失敗")
  void shouldFailWhenReservationStockPoolDoesNotExist() {
    UUID messageId = UUID.randomUUID();
    Order order = allocatedOrder();
    UUID stockPoolId = UUID.randomUUID();
    StockReservation reservation = StockReservation.create(
        UUID.randomUUID(),
        UUID.randomUUID(), order.getLines().get(0).getId(), stockPoolId, 3, now.minusSeconds(1));
    when(inboxRepo.claimIfNew(message(messageId))).thenReturn(true);
    when(reservationRepository.findActiveByOrderId(order.getId()))
        .thenReturn(List.of(reservation));
    when(stockPoolRepository.findById(stockPoolId)).thenReturn(Optional.empty());

    // 這是資料損毀，不是正常缺席：預留的外鍵指向 stock_pools，指不到就是有東西壞了。
    assertThatThrownBy(() -> usecase.handle(inbound(order.getId(), messageId)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("StockPool not found: " + stockPoolId);
    verifyNoInteractions(coordinator);
  }

  @Test
  @DisplayName("多筆預留指向同一批時只應查一次庫存")
  void shouldLoadEachBatchOnlyOnce() {
    UUID messageId = UUID.randomUUID();
    Order order = allocatedOrder();
    UUID lineId = order.getLines().get(0).getId();
    StockPool batch = StockFixtures.unexpiredBatch("SKU-1", 60, 30);
    when(inboxRepo.claimIfNew(message(messageId))).thenReturn(true);
    when(reservationRepository.findActiveByOrderId(order.getId())).thenReturn(List.of(
        StockReservation.create(UUID.randomUUID(), UUID.randomUUID(), lineId, batch.getId(), 20, now.minusSeconds(1)),
        StockReservation.create(UUID.randomUUID(), UUID.randomUUID(), lineId, batch.getId(), 10, now.minusSeconds(1))));
    when(stockPoolRepository.findById(batch.getId())).thenReturn(Optional.of(batch));

    usecase.handle(inbound(order.getId(), messageId));

    verify(stockPoolRepository).findById(batch.getId());
    assertThat(batch.getId()).isNotNull();
  }

  private Order allocatedOrder() {
    return OrderFixtures.allocatedOrder(
        UUID.randomUUID(), "SKU-1", 80, now.minusSeconds(10), now.minusSeconds(5));
  }


  private MessageMetadata message(UUID eventId) {
    return new MessageMetadata(eventId, "OrderCancelledIntegrationEvent");
  }

  private InboundCommand<ReleaseReservationCommand> inbound(UUID orderId, UUID eventId) {
    return new InboundCommand<>(new ReleaseReservationCommand(orderId), message(eventId));
  }
}
