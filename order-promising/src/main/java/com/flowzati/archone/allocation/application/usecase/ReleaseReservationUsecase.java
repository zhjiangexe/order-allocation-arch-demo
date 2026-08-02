package com.flowzati.archone.allocation.application.usecase;

import com.flowzati.archone.allocation.application.command.ReleaseReservationCommand;
import com.flowzati.archone.allocation.application.movement.MovementCanceller;
import com.flowzati.archone.common.inbox.InboxRepo;
import com.flowzati.archone.common.inbox.InboundCommand;
import jakarta.transaction.Transactional;
import java.time.Clock;
import org.springframework.stereotype.Service;

/**
 * 取消一張單：把它的搬運取消，鎖住的量還給庫存。
 *
 * <p>本身只做兩件事——冪等，然後把工作交出去。取消要碰哪些表、要濾掉什麼、要按什麼順序寫，
 * 全是「取消搬運」這個動作的內容，住在 {@link MovementCanceller}。
 *
 * <p>類別名還叫 {@code ReleaseReservation}，而預留已經不是一個獨立的東西了——它是一段搬運
 * 被鎖定的狀態。改名連同 {@code ReleaseReservationCommand} 與對外事件的處理鏈一起，排在第四
 * 個 change 的命名收斂。
 */
@Service
public class ReleaseReservationUsecase {

  private final InboxRepo inboxRepo;
  private final MovementCanceller movementCanceller;
  private final Clock clock;

  public ReleaseReservationUsecase(
      InboxRepo inboxRepo,
      MovementCanceller movementCanceller,
      Clock clock) {
    this.inboxRepo = inboxRepo;
    this.movementCanceller = movementCanceller;
    this.clock = clock;
  }

  @Transactional
  public void handle(InboundCommand<ReleaseReservationCommand> inbound) {
    if (!inboxRepo.claimIfNew(inbound.message())) {
      return;
    }
    movementCanceller.cancelFor(inbound.command().orderId(), clock.instant());
  }
}
