package com.flowzati.archone.allocation.application.usecase;

import com.flowzati.archone.allocation.application.coordinator.OrderAllocationCoordinator;
import com.flowzati.archone.allocation.domain.event.StockReplenished;
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
  private final OrderAllocationCoordinator allocationService;

  public ReplenishmentUsecase(Clock clock, Inbox inbox, OrderRepository orderRepository, StockPoolRepository stockPoolRepository, OrderAllocationCoordinator allocationService) {
    this.clock = clock;
    this.inbox = inbox;
    this.orderRepository = orderRepository;
    this.stockPoolRepository = stockPoolRepository;
    this.allocationService = allocationService;
  }


  @Transactional
  public void handle(StockReplenished event) {
    if (!inbox.claimIfNew(event.getEventId())) {
      return;
    }

    // 1. 加載庫存 Aggregate
    StockPool stockPool = stockPoolRepository.findBySku(event.getSku())
        .orElseThrow(() -> new IllegalStateException("StockPool not found for SKU: " + event.getSku()));

    // 2. 補充庫存
    stockPool.replenish(event.getQuantity());

    // 3. 取等待中的訂單
    List<Order> backorders = orderRepository.getPendingBySku(event.getSku());

    // 4. 調用應用層服務執行分配與持久化
    Instant now = clock.instant();
    allocationService.allocateAndSaveSuccesses(backorders, stockPool, now);
  }
}
