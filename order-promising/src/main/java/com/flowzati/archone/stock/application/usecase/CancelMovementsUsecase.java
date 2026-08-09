package com.flowzati.archone.stock.application.usecase;

import com.flowzati.archone.stock.application.command.CancelMovementsCommand;
import com.flowzati.archone.stock.application.movement.MovementCanceller;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

/**
 * 取消一張單：把它的搬運取消，鎖住的量還給庫存。
 *
 * <p>訊息冪等由 inbound decorator 負責；本 use case 把工作交給 {@link MovementCanceller}。
 * 取消要碰哪些表、要濾掉什麼、要按什麼順序寫，全是「取消搬運」這個動作的內容。
 *
 * <p>名字取自它做的事，與它委派的 {@code MovementCanceller} 對得起來。曾經叫
 * {@code ReleaseReservation}——那時預留還是一個獨立的東西，而現在它是一段搬運被鎖定的狀態，
 * 「釋放」因此沒有受詞。
 *
 * <p><b>對外事件 {@code OrderCancelledIntegrationEvent} 沒有跟著改</b>：那是 ordering 發的，
 * 說的是訂單被取消，與這一側怎麼稱呼它的處置無關。
 */
@Service
public class CancelMovementsUsecase {

  private final MovementCanceller movementCanceller;

  public CancelMovementsUsecase(MovementCanceller movementCanceller) {
    this.movementCanceller = movementCanceller;
  }

  /** Transport-neutral application entrypoint; inbound idempotency belongs to the caller boundary. */
  @Transactional
  public void execute(CancelMovementsCommand command) {
    cancel(command);
  }

  private void cancel(CancelMovementsCommand command) {
    movementCanceller.cancelForOrder(command.orderId());
  }
}
