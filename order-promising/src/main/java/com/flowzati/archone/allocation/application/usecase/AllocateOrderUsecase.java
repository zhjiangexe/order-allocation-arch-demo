package com.flowzati.archone.allocation.application.usecase;

import com.flowzati.archone.allocation.application.command.AllocateOrderCommand;
import com.flowzati.archone.allocation.application.coordinator.OrderAllocationCoordinator;
import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.service.AllocationOutcome;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.common.inbox.InboxRepo;
import com.flowzati.archone.common.time.BusinessCalendar;
import com.flowzati.archone.common.inbox.InboundCommand;
import com.flowzati.archone.common.inbox.MessageMetadata;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public class AllocateOrderUsecase {
  private final InboxRepo inboxRepo;
  private final OrderRepository orderRepository;
  private final StockPoolRepository stockPoolRepository;
  private final OrderAllocationCoordinator allocationCoordinator;
  private final Clock clock;
  private final BusinessCalendar businessCalendar;

  public AllocateOrderUsecase(
      InboxRepo inboxRepo,
      OrderRepository orderRepository,
      StockPoolRepository stockPoolRepository,
      OrderAllocationCoordinator allocationCoordinator,
      Clock clock,
      BusinessCalendar businessCalendar) {
    this.inboxRepo = inboxRepo;
    this.orderRepository = orderRepository;
    this.stockPoolRepository = stockPoolRepository;
    this.allocationCoordinator = allocationCoordinator;
    this.clock = clock;
    this.businessCalendar = businessCalendar;
  }

  @Transactional
  public void handle(InboundCommand<AllocateOrderCommand> inbound) {
    if (!inboxRepo.claimIfNew(inbound.message())) {
      return;
    }
    AllocateOrderCommand command = inbound.command();

    Order order = orderRepository.findById(command.orderId())
        .orElseThrow(() -> new IllegalStateException("Order not found: " + command.orderId()));
    if (order.getStatus() != OrderStatus.PENDING) {
      return;
    }

    // 一次配貨只取一個 (貨主, 倉, SKU) 的批,因此這裡踩在「這張單只碰一個 SKU」的假設上。
    // 收單政策目前保證它成立;放寬多 SKU 時,這裡要改成取多組批並做整籃判斷(見 roadmap R8)。
    String skuCode = order.requireSingleSku();
    Instant now = clock.instant();

    // 篩選與排序都在資料庫做。批數只會隨時間成長,把配不到的載進記憶體只為了丟掉是錯的
    // 方向;而且 (expiry_date, in_date, id) 正是索引的順序,資料庫本來就排好了。
    //
    // 一批可配的都沒有不是例外——那是「缺貨」這個正常結果,由配貨流程判定後掛帳。原本的
    // 「查無庫存池就丟例外」在分批之後會把每一次缺貨都變成訊息處理失敗。
    List<StockPool> allocatableBatches = stockPoolRepository.findAllocatableBatchesInFefoOrder(
        order.getOwnerId(),
        order.getDeliveryTerms().fulfillmentNodeId(),
        skuCode,
        businessCalendar.today());

    if (allocationCoordinator.allocateOrder(order, allocatableBatches, now) == AllocationOutcome.ALLOCATED) {
      return;
    }
    allocationCoordinator.backorderOrder(order, now);
  }
}
