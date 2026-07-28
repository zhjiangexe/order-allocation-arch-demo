package com.flowzati.archone.allocation.application.usecase;

import com.flowzati.archone.allocation.application.coordinator.OrderAllocationCoordinator;
import com.flowzati.archone.allocation.application.command.ReplenishStockCommand;
import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.common.inbox.InboxRepo;
import com.flowzati.archone.common.inbox.InboundCommand;
import com.flowzati.archone.common.inbox.MessageMetadata;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 補貨並在<strong>同一個交易內</strong>喚醒該貨主的缺貨佇列。
 *
 * <p><b>同交易不是效能取捨，是 FIFO 的實作機制。</b>若改成「補貨只加庫存、另發事件非同步
 * 喚醒」，在兩次 commit 之間任何新單都會走 {@code AllocateOrderUsecase} 直接吃掉剛補進來的
 * ATP，反超整個佇列。現況之所以不會，正是因為補貨與喚醒共用同一個 {@code StockPool} 的
 * 樂觀鎖，併發的新單會衝突重試。
 *
 * <p><b>已知缺口：喚醒批次沒有上限。</b>{@code findBackordersBySkuInFifoOrder()} 回傳整個
 * 佇列，一次補貨可能在單一交易內改動數百張訂單（見
 * {@code AllocationFifoReplenishmentBatchIntegrationTest} 的 500 張情境），而樂觀鎖全程
 * 暴露在衝突下。收尾在 roadmap R3 任務 8：上限加續做事件。
 *
 * <p>FIFO 保證的範圍、補貨事件為何是單筆，見 {@code docs/dom-promising-scope.md} 的
 * 「補貨的三個決定」。
 */
@Service
public class ReplenishmentUsecase {
  private final Clock clock;
  private final InboxRepo inboxRepo;
  private final OrderRepository orderRepository;
  private final StockPoolRepository stockPoolRepository;
  private final OrderAllocationCoordinator allocationCoordinator;

  public ReplenishmentUsecase(
      Clock clock,
      InboxRepo inboxRepo,
      OrderRepository orderRepository,
      StockPoolRepository stockPoolRepository,
      OrderAllocationCoordinator allocationCoordinator
  ) {
    this.clock = clock;
    this.inboxRepo = inboxRepo;
    this.orderRepository = orderRepository;
    this.stockPoolRepository = stockPoolRepository;
    this.allocationCoordinator = allocationCoordinator;
  }


  @Transactional
  public void handle(InboundCommand<ReplenishStockCommand> inbound) {
    if (!inboxRepo.claimIfNew(inbound.message())) {
      return;
    }
    ReplenishStockCommand command = inbound.command();

    // 1. 加載庫存 Aggregate
    StockPool stockPool = stockPoolRepository.findBySku(command.sku())
        .orElseThrow(() -> new IllegalStateException("StockPool not found for SKU: " + command.sku()));

    // 2. 依穩定 FIFO 順序取得缺貨訂單
    // 只喚醒這個貨主的佇列。已知的中間狀態:補進去的庫存仍是共用的——stock_pools 還沒有
    // owner_id,兩個貨主的同碼 SKU 共用同一列。佇列分開了,庫存還沒分開,後者屬 R3。
    List<Order> backorders =
        orderRepository.findBackordersBySkuInFifoOrder(command.ownerId(), command.sku());

    // 3. 由 Coordinator 統一執行補貨、分配與持久化
    Instant now = clock.instant();
    allocationCoordinator.replenishAndAllocateBackorders(backorders, stockPool, command.quantity(), now);
  }
}
