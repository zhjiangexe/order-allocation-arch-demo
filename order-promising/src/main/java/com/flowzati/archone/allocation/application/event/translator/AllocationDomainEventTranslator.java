package com.flowzati.archone.allocation.application.event.translator;

import com.flowzati.archone.allocation.application.event.PromisingEventTopics;
import com.flowzati.archone.allocation.application.event.BackorderCreatedIntegrationEvent;
import com.flowzati.archone.allocation.application.event.BackorderWakeRequestedIntegrationEvent;
import com.flowzati.archone.allocation.application.event.InventoryEventTopics;
import com.flowzati.archone.allocation.domain.event.BackorderWakeContinuationRequired;
import com.flowzati.archone.allocation.application.event.OrderAllocatedIntegrationEvent;
import com.flowzati.archone.allocation.domain.event.OrderAllocationCompleted;
import com.flowzati.archone.common.IdGenerator;
import com.flowzati.archone.common.outbox.OutboxAggregateTypes;
import com.flowzati.archone.common.outbox.OutboxAppender;
import com.flowzati.archone.common.outbox.OutboxDelivery;
import com.flowzati.archone.common.outbox.StockContentionKey;
import com.flowzati.archone.allocation.domain.event.OrderBackorderRecorded;

import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class AllocationDomainEventTranslator {

  private final OutboxAppender outboxAppender;

  public AllocationDomainEventTranslator(OutboxAppender outboxAppender) {
    this.outboxAppender = outboxAppender;
  }

  @EventListener
  public void translate(OrderAllocationCompleted event) {
    OrderAllocatedIntegrationEvent integration = new OrderAllocatedIntegrationEvent(
        IdGenerator.nextId(),
        event.orderId(),
        event.allocatedAt());
    outboxAppender.append(
        integration,
        OutboxAggregateTypes.ORDER,
        event.orderId().toString(),
        deliveryKeyedByOrder(event.orderId()),
        event.allocatedAt()
    );
  }

  /**
   * 缺貨。
   *
   * <p><b>監聽的是 allocation 自己的事實，不是 ordering 的 {@code OrderBackordered}。</b>
   * 後者現在由 ordering 消費這則對外事件之後才產生——監聽它會讓「發事件 → ordering 改狀態 →
   * 產生領域事件 → 又發事件」無限循環下去。兩個 context 各發各的，循環因此形成不了。
   */
  @EventListener
  public void translate(OrderBackorderRecorded event) {
    BackorderCreatedIntegrationEvent integration = new BackorderCreatedIntegrationEvent(
        IdGenerator.nextId(),
        event.orderId(),
        event.backorderedAt());
    outboxAppender.append(
        integration,
        OutboxAggregateTypes.ORDER,
        event.orderId().toString(),
        deliveryKeyedByOrder(event.orderId()),
        event.backorderedAt()
    );
  }

  /**
   * 續做喚醒。
   *
   * <p><b>key 一律是爭用群組，不套用 {@code partition-key-strategy}</b>——與配置結果事件相反。
   * 這則事件存在的唯一目的就是接續同一組庫存的上一輪喚醒，落到別的 partition 就會與它要接續
   * 的那一輪並行，而 single writer 正是靠同 key 取得的。補貨探針發的原始事件也是同一個 key，
   * 所以兩者由同一個 consumer 依序處理。
   *
   * <p>topic 也與補貨事件相同（{@code inventory.stock-events}），這樣「補貨」與「續做」在
   * Kafka 層是同一條隊伍。
   *
   * <p>aggregate 是 {@code StockPool} 而不是 {@code Order}：續做不屬於佇列裡的任何一張單。
   */
  @EventListener
  public void translate(BackorderWakeContinuationRequired event) {
    String contentionKey = StockContentionKey.of(event.ownerId(), event.nodeId());
    outboxAppender.append(
        new BackorderWakeRequestedIntegrationEvent(
            IdGenerator.nextId(), event.ownerId(), event.nodeId(), event.skuCode()),
        OutboxAggregateTypes.STOCK_POOL,
        contentionKey,
        new OutboxDelivery(InventoryEventTopics.STOCK_EVENTS, contentionKey),
        event.requestedAt()
    );
  }

  /**
   * 配置結果事件一律以 orderId 當 partition key，不套用
   * {@code archone.allocation.partition-key-strategy}。
   *
   * <p>{@code stock} 策略存在的目的，是讓會搶同一批庫存的下單事件收斂進同一個 partition，使
   * allocation consumer 成為那些庫存列的 single writer。{@code promising.allocation-events}
   * 在本 repo 沒有任何 consumer，沒有需要被保護的寫入端；為了「一致性」把策略套上來，
   * 會製造一個無人驗證、無人受益的行為分支。要改動這裡，先確認該 topic 已經有
   * consumer，而且它確實需要庫存維度的順序保證。
   */
  private static OutboxDelivery deliveryKeyedByOrder(UUID orderId) {
    return new OutboxDelivery(PromisingEventTopics.ALLOCATION_EVENTS, orderId.toString());
  }
}
