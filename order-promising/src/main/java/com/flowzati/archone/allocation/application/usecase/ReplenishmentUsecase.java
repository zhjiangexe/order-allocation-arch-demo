package com.flowzati.archone.allocation.application.usecase;

import com.flowzati.archone.allocation.application.coordinator.OrderAllocationCoordinator;
import com.flowzati.archone.allocation.application.event.StockReplenishedIntegrationEvent;
import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.common.inbox.Inbox;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public class ReplenishmentUsecase {
  private final Clock clock;
  private final Inbox inbox;
  private final OrderRepository orderRepository;
  private final StockPoolRepository stockPoolRepository;
  private final OrderAllocationCoordinator allocationCoordinator;

  public ReplenishmentUsecase(
      Clock clock,
      Inbox inbox,
      OrderRepository orderRepository,
      StockPoolRepository stockPoolRepository,
      OrderAllocationCoordinator allocationCoordinator
  ) {
    this.clock = clock;
    this.inbox = inbox;
    this.orderRepository = orderRepository;
    this.stockPoolRepository = stockPoolRepository;
    this.allocationCoordinator = allocationCoordinator;
  }


  @Transactional
  public void handle(StockReplenishedIntegrationEvent event) {
    if (!inbox.claimIfNew(event.getEventId())) {
      return;
    }

    // 1. 加載庫存 Aggregate
    StockPool stockPool = stockPoolRepository.findBySku(event.getSku())
        .orElseThrow(() -> new IllegalStateException("StockPool not found for SKU: " + event.getSku()));

    // 2. 依穩定 FIFO 順序取得缺貨訂單
    List<Order> backorders = orderRepository.findBackordersBySkuInFifoOrder(event.getSku());

    // 3. 由 Coordinator 統一執行補貨、分配與持久化
    Instant now = clock.instant();
    allocationCoordinator.replenishAndAllocateBackorders(backorders, stockPool, event.getQuantity(), now);
  }
}
