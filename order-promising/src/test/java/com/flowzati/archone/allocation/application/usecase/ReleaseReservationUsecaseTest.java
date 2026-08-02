package com.flowzati.archone.allocation.application.usecase;

import com.flowzati.archone.allocation.application.command.ReleaseReservationCommand;
import com.flowzati.archone.allocation.application.movement.MovementCanceller;
import com.flowzati.archone.common.inbox.InboxRepo;
import com.flowzati.archone.common.inbox.InboundCommand;
import com.flowzati.archone.common.inbox.MessageMetadata;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReleaseReservationUsecaseTest {

  private final Instant now = Instant.parse("2026-07-24T01:00:00Z");

  private InboxRepo inboxRepo;
  private MovementCanceller movementCanceller;
  private ReleaseReservationUsecase usecase;

  @BeforeEach
  void setUp() {
    inboxRepo = mock(InboxRepo.class);
    movementCanceller = mock(MovementCanceller.class);
    usecase = new ReleaseReservationUsecase(
        inboxRepo, movementCanceller, Clock.fixed(now, ZoneId.of("UTC")));
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
  @DisplayName("應把取消整張委派出去，時刻用這一次交易的 now")
  void shouldDelegateTheWholeCancellation() {
    UUID messageId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    when(inboxRepo.claimIfNew(message(messageId))).thenReturn(true);

    usecase.handle(inbound(orderId, messageId));

    // 這支 usecase 只剩兩件事：冪等，然後把工作交出去。取消要碰哪些表、要濾掉什麼、要按什麼
    // 順序寫，全是「取消搬運」這個動作的內容——那些性質由 MovementCancellerTest 守著，
    // 在這裡重測一次只會讓同一條性質有兩個會一起壞掉的證人。
    verify(movementCanceller).cancelFor(orderId, now);
  }

  private MessageMetadata message(UUID eventId) {
    return new MessageMetadata(eventId, "OrderCancelledIntegrationEvent");
  }

  private InboundCommand<ReleaseReservationCommand> inbound(UUID orderId, UUID eventId) {
    return new InboundCommand<>(new ReleaseReservationCommand(orderId), message(eventId));
  }
}
