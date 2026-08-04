package com.flowzati.archone.ordering.application.event.translator;

import com.flowzati.archone.common.IdGenerator;
import com.flowzati.archone.common.outbox.OutboxAggregateTypes;
import com.flowzati.archone.common.outbox.OutboxAppender;
import com.flowzati.archone.common.outbox.OutboxDelivery;
import com.flowzati.archone.common.outbox.StockContentionKey;
import com.flowzati.archone.ordering.application.event.OrderCancelledIntegrationEvent;
import com.flowzati.archone.ordering.application.event.OrderPlacedIntegrationEvent;
import com.flowzati.archone.ordering.application.event.OrderingEventTopics;
import com.flowzati.archone.ordering.domain.event.OrderCancelled;
import com.flowzati.archone.ordering.domain.event.OrderPlaced;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class OrderingDomainEventTranslator {

  private static final String STOCK_STRATEGY = "stock";
  private static final Logger log = LoggerFactory.getLogger(OrderingDomainEventTranslator.class);

  private final OutboxAppender outboxAppender;
  private final String partitionKeyStrategy;

  public OrderingDomainEventTranslator(
      OutboxAppender outboxAppender,
      @Value("${archone.allocation.partition-key-strategy:order-id}") String partitionKeyStrategy
  ) {
    this.outboxAppender = outboxAppender;
    this.partitionKeyStrategy = partitionKeyStrategy;
    log.info("archone.allocation.partition-key-strategy={}", partitionKeyStrategy);
  }

  @EventListener
  public void translate(OrderPlaced event) {
    // 貨主與倉只在 partition key 用到——對外事件兩個都不帶。
    OrderPlacedIntegrationEvent integration =
        new OrderPlacedIntegrationEvent(IdGenerator.nextId(), event.orderId(), event.receivedAt());
    String partitionKey =
        partitionKey(event.orderId(), event.ownerId(), event.facilityId());
    outboxAppender.append(
        integration,
        OutboxAggregateTypes.ORDER,
        event.orderId().toString(),
        new OutboxDelivery(OrderingEventTopics.ORDER_EVENTS, partitionKey),
        event.receivedAt()
    );
  }

  @EventListener
  public void translate(OrderCancelled event) {
    OrderCancelledIntegrationEvent integration =
        new OrderCancelledIntegrationEvent(IdGenerator.nextId(), event.orderId(), event.cancelledAt());
    String partitionKey =
        partitionKey(event.orderId(), event.ownerId(), event.facilityId());
    outboxAppender.append(
        integration,
        OutboxAggregateTypes.ORDER,
        event.orderId().toString(),
        new OutboxDelivery(OrderingEventTopics.ORDER_EVENTS, partitionKey),
        event.cancelledAt()
    );
  }

  /**
   * {@code stock} 策略下以 {@link StockContentionKey}（{@code (貨主, 倉)}）當 partition key，
   * 讓所有會碰到同一批庫存的事件收斂進同一個 partition（single-writer，見
   * docs/superpowers/specs/2026-07-26-v3-single-writer-design.md）；預設（或任何非
   * {@code "stock"} 的值）沿用 v1 的 orderId。
   *
   * <p>這只決定 Kafka message key，不影響 outbox row 的 {@code aggregateid}——後者恆為
   * orderId，因為事件所屬的 aggregate 是 Order，與分區策略無關。
   *
   * <p><b>key 的正確形狀由「哪些庫存列會被同一次交易碰到」決定，不是由訂單決定</b>，因此組成
   * 的規則收在 {@link StockContentionKey}，而不是散在這裡與補貨探針各寫一次。
   *
   * <p><b>這個策略曾經含 SKU，而那個版本有到期日。</b>per-SKU 的 key 與 ship-complete 根本
   * 衝突：整籃原子判斷要在同一個交易裡檢查所有 SKU 的 ATP，而 per-SKU 分區的保證是「同一個
   * SKU 的事件由同一個 writer 序列化」，跨 SKU 的交易必然跨越多個 writer 的管轄。
   *
   * <p>拿掉 SKU 之後那個到期日消失了——一張單不管跨幾個 SKU 都只屬於一個 {@code (貨主, 倉)}，
   * **一個 writer 看得到整張單**。因此本策略不再需要「多 SKU 時退場」的出路，也不再需要把
   * SKU 延後求值。
   *
   * <p>換更細或更複雜的 key 救不回原本那個問題：SKU 集合雜湊成 key 不行（同一個 SKU 出現在
   * 多種組合裡）、按 SKU 拆成多則事件也不行（拆開之後沒有人看得到整張單）。
   * <b>問題不在 key 的組成，在「一次交易碰多個資源」與「一個 key 只指向一個 partition」之間
   * 的矛盾</b>——而唯一化解它的方向是把 key 變粗到覆蓋整個交易可能碰到的範圍。
   *
   * <p>代價與量測見 {@link StockContentionKey}。
   */
  private String partitionKey(UUID orderId, UUID ownerId, UUID facilityId) {
    return STOCK_STRATEGY.equals(partitionKeyStrategy)
        ? StockContentionKey.of(ownerId, facilityId)
        : orderId.toString();
  }
}
