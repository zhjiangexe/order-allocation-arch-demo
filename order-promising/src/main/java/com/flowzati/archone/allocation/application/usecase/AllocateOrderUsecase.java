package com.flowzati.archone.allocation.application.usecase;

import com.flowzati.archone.allocation.application.command.AllocateOrderCommand;
import com.flowzati.archone.allocation.application.coordinator.OrderAllocationCoordinator;
import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.common.inbox.InboxRepo;
import com.flowzati.archone.common.inbox.InboundCommand;
import com.flowzati.archone.common.inbox.MessageMetadata;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

@Service
public class AllocateOrderUsecase {
  private final InboxRepo inboxRepo;
  private final OrderRepository orderRepository;
  private final StockPoolRepository stockPoolRepository;
  private final OrderAllocationCoordinator allocationCoordinator;
  private final Clock clock;

  public AllocateOrderUsecase(
      InboxRepo inboxRepo,
      OrderRepository orderRepository,
      StockPoolRepository stockPoolRepository,
      OrderAllocationCoordinator allocationCoordinator,
      Clock clock) {
    this.inboxRepo = inboxRepo;
    this.orderRepository = orderRepository;
    this.stockPoolRepository = stockPoolRepository;
    this.allocationCoordinator = allocationCoordinator;
    this.clock = clock;
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

    // 一次配貨只取一個庫存池,因此這裡踩在「這張單只碰一個 SKU」的假設上。收單政策目前
    // 保證它成立;放寬多 SKU 時,這裡要改成取多個池並做整籃判斷(見 roadmap R8)。
    String skuCode = order.requireSingleSku();
    StockPool stockPool = stockPoolRepository.findBySku(skuCode)
        .orElseThrow(() -> new IllegalStateException("StockPool not found for SKU: " + skuCode));

    Instant now = clock.instant();

    if (allocationCoordinator.allocateOrder(order, stockPool, now).isPresent()) {
      return;
    }
    allocationCoordinator.backorderOrder(order, now);
  }
}
