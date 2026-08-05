package com.flowzati.archone.stock.application.usecase;

import com.flowzati.archone.common.ddd.DomainEvent;
import com.flowzati.archone.stock.application.command.AllocateOrderCommand;
import com.flowzati.archone.stock.application.movement.MovementAssigner;
import com.flowzati.archone.stock.application.movement.StockOperationRecorder;
import com.flowzati.archone.stock.domain.event.OrderAllocationCompleted;
import com.flowzati.archone.stock.domain.event.OrderBackorderRecorded;
import com.flowzati.archone.stock.domain.model.StockMove;
import com.flowzati.archone.stock.domain.service.AllocationOutcome;
import com.flowzati.archone.common.inbox.InboxRepo;
import com.flowzati.archone.common.inbox.InboundCommand;
import com.flowzati.archone.stock.domain.model.Demand;
import com.flowzati.archone.stock.domain.repository.DemandRepository;
import jakarta.transaction.Transactional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class AllocateOrderUsecase {
  private final InboxRepo inboxRepo;
  private final DemandRepository demandRepository;
  private final StockOperationRecorder stockOperationRecorder;
  private final MovementAssigner movementAssigner;
  private final ApplicationEventPublisher eventPublisher;
  private final Clock clock;

  public AllocateOrderUsecase(
      InboxRepo inboxRepo,
      DemandRepository demandRepository,
      StockOperationRecorder stockOperationRecorder,
      MovementAssigner movementAssigner,
      ApplicationEventPublisher eventPublisher,
      Clock clock) {
    this.inboxRepo = inboxRepo;
    this.demandRepository = demandRepository;
    this.stockOperationRecorder = stockOperationRecorder;
    this.movementAssigner = movementAssigner;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  @Transactional
  public void handle(InboundCommand<AllocateOrderCommand> inbound) {
    if (!inboxRepo.claimIfNew(inbound.message())) {
      return;
    }
    AllocateOrderCommand command = inbound.command();

    // 查的是**還沒被執行層接手的行**。已經建了搬運的行不會出現在 demand_lines 裡，所以
    // 「這張單還需不需要接手」由 view 回答——不看訂單狀態，那是落後視圖，拿它當閘門會讓
    // 同一筆需求被建兩次搬運。
    //
    // 查無需求是正常結果而非錯誤：這則命令重送、或訂單已被取消，都會走到這裡。
    Optional<Demand> demandOpt = demandRepository.findByOrderId(command.orderId());
    if (demandOpt.isEmpty()) {
      return;
    }
    Demand demand = demandOpt.get();
    Instant now = clock.instant();

    // **先建搬運，再配貨。** 即使一件貨都沒有也要建——那讓「還在等貨」成為一列真實資料而
    // 不是一個查詢的副產物，而一張永遠配不到的單因此留得下痕跡（含它等了多久）。
    //
    // 建好的搬運直接交給下一步：鎖定要做的是把它們轉狀態，不必回頭再讀一次。
    List<StockMove> moves = stockOperationRecorder.recordOutbound(demand, now);

    boolean isAllocated = movementAssigner.assign(demand, moves, now) == AllocationOutcome.ALLOCATED;
    DomainEvent event = isAllocated ?
        new OrderAllocationCompleted(demand.orderId(), now) :
        new OrderBackorderRecorded(demand.orderId(), now);
    eventPublisher.publishEvent(event);
  }
}
