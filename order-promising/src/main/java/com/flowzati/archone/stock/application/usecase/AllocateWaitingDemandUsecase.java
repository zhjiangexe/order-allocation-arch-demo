package com.flowzati.archone.stock.application.usecase;

import com.flowzati.archone.promising.time.AppClock;
import com.flowzati.archone.stock.application.command.AllocateWaitingDemandCommand;
import com.flowzati.archone.stock.application.event.AllocationDomainEventPublisher;
import com.flowzati.archone.stock.application.movement.MovementAssigner;
import com.flowzati.archone.stock.application.movement.AssignedDemand;
import com.flowzati.archone.stock.domain.event.OrderAllocationCompleted;
import com.flowzati.archone.stock.domain.model.StockMove;
import com.flowzati.archone.stock.domain.model.StockPool;
import com.flowzati.archone.stock.domain.repository.StockMoveRepository;
import com.flowzati.archone.stock.domain.repository.StockPoolRepository;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 執行一輪等待需求配貨的 transaction owner。
 *
 * <p>availability 事件由 inbound decorator 在同一 transaction 先 claim Inbox；scheduler
 * 則直接傳入 transport-neutral command。兩種入口都以各自的一個交易完成有上限的配貨與
 * 完成事實。剩餘工作由後續 scheduler 掃描收斂，不發布 continuation control event。
 */
@Service
public class AllocateWaitingDemandUsecase {

  private static final Logger log = LoggerFactory.getLogger(AllocateWaitingDemandUsecase.class);

  private final StockPoolRepository stockPoolRepository;
  private final StockMoveRepository stockMoveRepository;
  private final MovementAssigner movementAssigner;
  private final AllocationDomainEventPublisher eventPublisher;
  private final AppClock appClock;
  private final int allocationLimit;

  public AllocateWaitingDemandUsecase(
      StockPoolRepository stockPoolRepository,
      StockMoveRepository stockMoveRepository,
      MovementAssigner movementAssigner,
      AllocationDomainEventPublisher eventPublisher,
      AppClock appClock,
      @Value("${archone.allocation.waiting-demand-batch-limit:200}") int allocationLimit
  ) {
    if (allocationLimit <= 0) {
      throw new IllegalArgumentException("Waiting-demand allocation limit must be positive");
    }
    this.stockPoolRepository = stockPoolRepository;
    this.stockMoveRepository = stockMoveRepository;
    this.movementAssigner = movementAssigner;
    this.eventPublisher = eventPublisher;
    this.appClock = appClock;
    this.allocationLimit = allocationLimit;
  }

  /** Scheduler reconciliation has no transport message to claim, but uses the same transaction. */
  @Transactional
  public void execute(AllocateWaitingDemandCommand command) {
    allocate(command);
  }

  private void allocate(AllocateWaitingDemandCommand command) {
    // ① 先確認這個 owner/location/SKU scope 是否存在可用庫存，作為快速預檢。
    // 這不代表每個等待中的訂單都已滿足；完整的數量與 ship-complete 判斷
    // 仍由後續的 MovementAssigner/AllocationService 負責。
    List<StockPool> allocatableBatchesInFefoOrder = stockPoolRepository.findAllocatableBatchesInFefoOrder(
        command.ownerId(), command.locationId(), command.sku(), appClock.today());
    if (allocatableBatchesInFefoOrder.isEmpty()) {
      log.atDebug()
          .addKeyValue("ownerId", command.ownerId())
          .addKeyValue("facilityId", command.facilityId())
          .addKeyValue("locationId", command.locationId())
          .addKeyValue("sku", command.sku())
          .log("No allocatable stock for waiting-demand scope");
      // 沒有可配庫存是正常的 no-op；等待中的需求維持 CONFIRMED，留待補貨事件
      // 或 reconciliation scheduler 下一次觸發。
      return;
    }

    // ② 依需求 FIFO 順序取出本輪要處理的 waiting moves，並以 allocationLimit
    // 限制單次交易量，避免一次重算造成過大的交易與鎖定範圍。
    List<StockMove> waiting = stockMoveRepository.findWaitingInFifoOrder(
        command.ownerId(), command.locationId(), command.sku(), allocationLimit);
    if (waiting.isEmpty()) {
      log.atDebug()
          .addKeyValue("ownerId", command.ownerId())
          .addKeyValue("facilityId", command.facilityId())
          .addKeyValue("locationId", command.locationId())
          .addKeyValue("sku", command.sku())
          .log("No waiting stock moves for allocation scope");
      // 庫存可能已被其他流程先配走，或本輪查詢已沒有待配需求；這同樣是正常 no-op。
      return;
    }

    // ③ 執行實際配貨：在 FIFO 需求與 FEFO 庫存規則下，重新檢查可配數量並更新
    // move 狀態。由於可能有並行配貨，查詢到的數量不保證等於最後成功配出的數量。
    Instant now = appClock.instant();
    List<AssignedDemand> assigned = movementAssigner.assignWaitingBatch(waiting, now);
    if (assigned.isEmpty()) {
      // 本輪沒有完整配出的需求，不發布完成事件；moves 保持等待狀態，稍後再重試。
      return;
    }

    // ④ 只為實際完成配貨的 demand 發布事件。每個事件代表一張訂單已完成配貨，
    // 讓下游履行流程可以建立 shipment；未出現在 assigned 的需求不會被誤推進。
    for (AssignedDemand allocation : assigned) {
      eventPublisher.publish(OrderAllocationCompleted.from(allocation.demand(), allocation.moves(), now));
    }
  }
}
