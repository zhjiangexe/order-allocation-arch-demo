package com.flowzati.archone.stock.application.usecase;

import com.flowzati.archone.promising.time.AppClock;
import com.flowzati.archone.stock.application.command.ConfirmStockReceiptCommand;
import com.flowzati.archone.stock.application.event.AllocationDomainEventPublisher;
import com.flowzati.archone.stock.application.movement.MovementCompleter;
import com.flowzati.archone.stock.application.movement.StockOperationRecorder;
import com.flowzati.archone.stock.domain.event.StockAvailabilityIncreased;
import com.flowzati.archone.stock.domain.model.StockMove;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 同步確認一段式收貨。
 *
 * <p>inbound picking、move、move line、實體庫存增加與 availability Outbox 事實在同一個
 * 交易內完成。庫存只由完成搬運的明細增加，不另開直接寫數量的路徑。
 * 呼叫者指定實際收貨庫位；本 use case 在交易內驗證它是該 Facility 的 internal location。
 *
 * <p>這裡不執行 outbound 配貨。availability Integration Event 與 reconciliation scheduler
 * 都在收貨提交後呼叫 {@link AllocateWaitingDemandUsecase}，避免 inbound 交易承擔等待佇列的負載。
 */
@Service
public class ConfirmStockReceiptUsecase {

  private final AppClock appClock;
  private final StockOperationRecorder stockOperationRecorder;
  private final MovementCompleter movementCompleter;
  private final AllocationDomainEventPublisher eventPublisher;

  public ConfirmStockReceiptUsecase(
      AppClock appClock,
      StockOperationRecorder stockOperationRecorder,
      MovementCompleter movementCompleter,
      AllocationDomainEventPublisher eventPublisher
  ) {
    this.appClock = appClock;
    this.stockOperationRecorder = stockOperationRecorder;
    this.movementCompleter = movementCompleter;
    this.eventPublisher = eventPublisher;
  }

  /** Transport-neutral application entrypoint; request idempotency belongs to the caller boundary. */
  @Transactional
  public void execute(ConfirmStockReceiptCommand command) {
    receive(command);
  }

  private void receive(ConfirmStockReceiptCommand command) {
    Instant now = appClock.instant();

    List<StockMove> incoming = stockOperationRecorder.recordInbound(
        command.facilityId(), command.ownerId(), command.locationId(), command.sku(), command.quantity(), now);

    movementCompleter.complete(
        incoming, new MovementCompleter.BatchIdentity(command.inDate(), command.expiryDate()), now);

    eventPublisher.publish(new StockAvailabilityIncreased(
        command.ownerId(), command.facilityId(), command.locationId(),
        command.sku(), command.quantity(), now));
  }
}
