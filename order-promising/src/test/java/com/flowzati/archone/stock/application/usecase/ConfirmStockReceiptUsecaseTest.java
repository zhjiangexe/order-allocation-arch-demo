package com.flowzati.archone.stock.application.usecase;

import com.flowzati.archone.common.inbox.InboundCommand;
import com.flowzati.archone.common.inbox.InboxRepo;
import com.flowzati.archone.common.inbox.MessageMetadata;
import com.flowzati.archone.common.time.AppClock;
import com.flowzati.archone.stock.application.command.ConfirmStockReceiptCommand;
import com.flowzati.archone.stock.application.movement.MovementCompleter;
import com.flowzati.archone.stock.application.movement.StockOperationRecorder;
import com.flowzati.archone.stock.domain.event.StockAvailabilityIncreased;
import com.flowzati.archone.stock.domain.model.StockFixtures;
import com.flowzati.archone.stock.domain.model.StockMove;
import com.flowzati.archone.testsupport.MovementFixtures;
import com.flowzati.archone.testsupport.OrderFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("確認一段式收貨並發布庫存可用事實")
class ConfirmStockReceiptUsecaseTest {

  private static final String SKU = "SKU-1";
  private static final Instant NOW = Instant.parse("2026-07-21T23:00:00Z");

  private InboxRepo inboxRepo;
  private StockOperationRecorder stockOperationRecorder;
  private MovementCompleter movementCompleter;
  private ApplicationEventPublisher eventPublisher;
  private ConfirmStockReceiptUsecase usecase;

  @BeforeEach
  void setUp() {
    inboxRepo = mock(InboxRepo.class);
    stockOperationRecorder = mock(StockOperationRecorder.class);
    movementCompleter = mock(MovementCompleter.class);
    eventPublisher = mock(ApplicationEventPublisher.class);
    usecase = new ConfirmStockReceiptUsecase(
        new AppClock(Clock.fixed(NOW, ZoneId.of("UTC")), "Asia/Taipei"),
        inboxRepo, stockOperationRecorder, movementCompleter,
        eventPublisher);
  }

  @Test
  @DisplayName("同一交易依序記錄、完成 inbound movement，再發布可用庫存事實")
  void shouldCompleteInboundMovementBeforePublishingAvailability() {
    ConfirmStockReceiptCommand command = command();
    InboundCommand<ConfirmStockReceiptCommand> inbound = inbound(command, UUID.randomUUID());
    StockMove move = inboundMove(command.quantity());
    when(inboxRepo.claimIfNew(inbound.message())).thenReturn(true);
    when(stockOperationRecorder.recordInbound(
        command.facilityId(), command.ownerId(), OrderFixtures.LOCATION_ID, SKU,
        command.quantity(), NOW)).thenReturn(List.of(move));

    usecase.handle(inbound);

    InOrder order = inOrder(stockOperationRecorder, movementCompleter, eventPublisher);
    order.verify(stockOperationRecorder).recordInbound(
        command.facilityId(), command.ownerId(), OrderFixtures.LOCATION_ID, SKU,
        command.quantity(), NOW);
    order.verify(movementCompleter).complete(
        List.of(move),
        new MovementCompleter.BatchIdentity(command.inDate(), command.expiryDate()),
        NOW);
    order.verify(eventPublisher).publishEvent(new StockAvailabilityIncreased(
        command.ownerId(), command.facilityId(), OrderFixtures.LOCATION_ID,
        SKU, command.quantity(), NOW));
  }

  @Test
  @DisplayName("重複請求不重複收貨，也不重複發布可用庫存事實")
  void shouldDoNothingWhenTheMessageWasAlreadyClaimed() {
    InboundCommand<ConfirmStockReceiptCommand> inbound = inbound(command(), UUID.randomUUID());
    when(inboxRepo.claimIfNew(inbound.message())).thenReturn(false);

    usecase.handle(inbound);

    verifyNoInteractions(stockOperationRecorder, movementCompleter, eventPublisher);
  }

  @Test
  @DisplayName("指定位置不屬於 Facility 時拒絕收貨，且不建立任何 movement")
  void shouldRejectALocationOutsideTheFacility() {
    ConfirmStockReceiptCommand command = command();
    InboundCommand<ConfirmStockReceiptCommand> inbound = inbound(command, UUID.randomUUID());
    when(inboxRepo.claimIfNew(inbound.message())).thenReturn(true);

    assertThatThrownBy(() -> usecase.handle(inbound))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("is not an internal location of facility");

    verifyNoInteractions(stockOperationRecorder, movementCompleter, eventPublisher);
  }

  private ConfirmStockReceiptCommand command() {
    return new ConfirmStockReceiptCommand(
        OrderFixtures.OWNER_ID, OrderFixtures.FACILITY_ID, OrderFixtures.LOCATION_ID, SKU,
        StockFixtures.ARRIVED_ON, StockFixtures.EXPIRES_ON, 10);
  }

  private StockMove inboundMove(int quantity) {
    return StockMove.confirmed(
        UUID.randomUUID(), UUID.randomUUID(), OrderFixtures.OWNER_ID, SKU,
        MovementFixtures.SUPPLIERS_LOCATION_ID, OrderFixtures.LOCATION_ID, null, quantity, NOW);
  }

  private InboundCommand<ConfirmStockReceiptCommand> inbound(
      ConfirmStockReceiptCommand command, UUID receiptId) {
    return new InboundCommand<>(
        command, new MessageMetadata(receiptId, "ConfirmStockReceiptRequest"));
  }
}
