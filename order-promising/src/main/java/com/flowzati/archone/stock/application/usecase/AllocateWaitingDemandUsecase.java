package com.flowzati.archone.stock.application.usecase;

import com.flowzati.archone.promising.time.AppClock;
import com.flowzati.archone.stock.application.command.AllocateWaitingDemandCommand;
import com.flowzati.archone.stock.application.event.AllocationDomainEventPublisher;
import com.flowzati.archone.stock.application.movement.MovementAssigner;
import com.flowzati.archone.stock.application.movement.AssignedDemand;
import com.flowzati.archone.stock.domain.event.OrderAllocationCompleted;
import com.flowzati.archone.stock.domain.model.Demand;
import com.flowzati.archone.stock.domain.model.StockMove;
import com.flowzati.archone.stock.domain.model.StockPool;
import com.flowzati.archone.stock.domain.repository.StockMoveRepository;
import com.flowzati.archone.stock.domain.repository.StockPoolRepository;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 執行一輪等待需求配貨的 transaction owner。
 *
 * <p>availability 事件由 inbound decorator 在同一 transaction 先 claim Inbox；scheduler
 * 則直接傳入 transport-neutral command。兩種入口都以各自的一個交易完成有上限的配貨與
 * 完成事實。剩餘工作由後續 scheduler 掃描收斂，不發布 continuation control event。
 */
@Service
public class AllocateWaitingDemandUsecase {

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
    List<StockPool> allocatableBatchesInFefoOrder = stockPoolRepository.findAllocatableBatchesInFefoOrder(
        command.ownerId(), command.locationId(), command.sku(), appClock.today());
    if (allocatableBatchesInFefoOrder.isEmpty()) {
      return;
    }

    List<StockMove> waiting = stockMoveRepository.findWaitingInFifoOrder(
        command.ownerId(), command.locationId(), command.sku(), allocationLimit);
    if (waiting.isEmpty()) {
      return;
    }

    Instant now = appClock.instant();
    List<AssignedDemand> assigned = movementAssigner.assignWaitingBatch(waiting, now);
    assigned.forEach(allocation -> eventPublisher.publish(
        OrderAllocationCompleted.from(allocation.demand(), allocation.moves(), now)));
  }
}
