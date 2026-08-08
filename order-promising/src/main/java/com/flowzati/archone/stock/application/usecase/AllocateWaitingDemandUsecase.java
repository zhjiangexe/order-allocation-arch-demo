package com.flowzati.archone.stock.application.usecase;

import com.flowzati.archone.messaging.api.InboundCommand;
import com.flowzati.archone.messaging.inbox.InboxRepo;
import com.flowzati.archone.promising.time.AppClock;
import com.flowzati.archone.stock.application.command.AllocateWaitingDemandCommand;
import com.flowzati.archone.stock.application.event.AllocationDomainEventPublisher;
import com.flowzati.archone.stock.application.movement.MovementAssigner;
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
 * <p>availability 事件會先在 Inbox claim；scheduler 則直接傳入 transport-neutral
 * command。兩種入口都以各自的一個交易完成有上限的配貨與完成事實。剩餘工作由後續
 * scheduler 掃描收斂，不發布 continuation control event。
 */
@Service
public class AllocateWaitingDemandUsecase {

  private final InboxRepo inboxRepo;
  private final StockPoolRepository stockPoolRepository;
  private final StockMoveRepository stockMoveRepository;
  private final MovementAssigner movementAssigner;
  private final AllocationDomainEventPublisher eventPublisher;
  private final AppClock appClock;
  private final int allocationLimit;

  public AllocateWaitingDemandUsecase(
      InboxRepo inboxRepo,
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
    this.inboxRepo = inboxRepo;
    this.stockPoolRepository = stockPoolRepository;
    this.stockMoveRepository = stockMoveRepository;
    this.movementAssigner = movementAssigner;
    this.eventPublisher = eventPublisher;
    this.appClock = appClock;
    this.allocationLimit = allocationLimit;
  }

  @Transactional
  public void handle(InboundCommand<AllocateWaitingDemandCommand> inbound) {
    if (!inboxRepo.claimIfNew(inbound.message())) {
      return;
    }
    allocate(inbound.command());
  }

  /** Scheduler reconciliation has no transport message to claim, but uses the same transaction. */
  @Transactional
  public void handle(AllocateWaitingDemandCommand command) {
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
    List<UUID> allocatedOrderIds = movementAssigner.assignWaitingBatch(waiting, now).stream()
        .map(Demand::orderId)
        .toList();
    allocatedOrderIds.forEach(
        orderId -> eventPublisher.publish(new OrderAllocationCompleted(orderId, now)));
  }
}
