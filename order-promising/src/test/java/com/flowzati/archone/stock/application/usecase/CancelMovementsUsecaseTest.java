package com.flowzati.archone.stock.application.usecase;

import com.flowzati.archone.stock.application.command.CancelMovementsCommand;
import com.flowzati.archone.stock.application.movement.MovementCanceller;
import com.flowzati.archone.common.inbox.InboxRepo;
import com.flowzati.archone.common.inbox.InboundCommand;
import com.flowzati.archone.common.inbox.MessageMetadata;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CancelMovementsUsecaseTest {

  private InboxRepo inboxRepo;
  private MovementCanceller movementCanceller;
  private CancelMovementsUsecase usecase;

  @BeforeEach
  void setUp() {
    inboxRepo = mock(InboxRepo.class);
    movementCanceller = mock(MovementCanceller.class);
    usecase = new CancelMovementsUsecase(inboxRepo, movementCanceller);
  }

  @Test
  @DisplayName("訊息已處理過時不應再執行取消")
  void shouldDoNothingWhenMessageWasAlreadyHandled() {
    UUID messageId = UUID.randomUUID();
    when(inboxRepo.claimIfNew(message(messageId))).thenReturn(false);

    usecase.handle(inbound(UUID.randomUUID(), messageId));

    verifyNoInteractions(movementCanceller);
  }

  @Test
  @DisplayName("應把這張單的搬運取消委派出去")
  void shouldDelegateTheWholeCancellation() {
    UUID messageId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    when(inboxRepo.claimIfNew(message(messageId))).thenReturn(true);

    usecase.handle(inbound(orderId, messageId));

    // 這支 usecase 只剩兩件事：冪等，然後把工作交出去。取消要碰哪些表、要濾掉什麼、要按什麼
    // 順序寫，全是「取消搬運」這個動作的內容——那些性質由 MovementCancellerTest 守著，
    // 在這裡重測一次只會讓同一條性質有兩個會一起壞掉的證人。
    verify(movementCanceller).cancelForOrder(orderId);
  }

  private MessageMetadata message(UUID eventId) {
    return new MessageMetadata(eventId, "OrderCancelledIntegrationEvent");
  }

  private InboundCommand<CancelMovementsCommand> inbound(UUID orderId, UUID eventId) {
    return new InboundCommand<>(new CancelMovementsCommand(orderId), message(eventId));
  }
}
