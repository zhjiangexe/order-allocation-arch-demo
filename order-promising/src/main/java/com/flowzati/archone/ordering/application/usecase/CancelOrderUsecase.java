package com.flowzati.archone.ordering.application.usecase;

import com.flowzati.archone.ordering.application.event.OrderingDomainEventPublisher;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 取消一張訂單。
 *
 * <p><b>目前沒有任何呼叫端</b>——沒有 REST endpoint、沒有 Kafka handler，前端也還沒有取消按鈕。
 * 這條流程「只有出海口沒有進水口」：{@code OrderCancelled} 領域事件會被翻成
 * {@code OrderCancelledIntegrationEvent}、由 allocation 消費並釋放預留，那一整段都已實作且有
 * 測試，只是沒有東西觸發它。
 *
 * <p>預定的入口是 <b>demo controller 加上操作台的取消按鈕</b>。寫在這裡是因為「沒人呼叫」很容易
 * 被誤讀成死程式而刪掉。
 *
 * <p><b>查無訂單時拋 {@link IllegalStateException}，而它該被映射成什麼由 entrypoint 決定</b>，
 * 不是這一層：HTTP 入口該回 404 而不是 500；Kafka 入口則反而該讓它落 DLT——上游取消一張我們沒
 * 收到的單，靜默忽略等於丟掉一個訊號。同一個例外在兩種入口下需要相反的處置，所以這裡只負責
 * 誠實地拋。
 */
@Service
public class CancelOrderUsecase {

  private final OrderRepository orderRepository;
  private final OrderingDomainEventPublisher eventPublisher;

  public CancelOrderUsecase(
      OrderRepository orderRepository,
      OrderingDomainEventPublisher eventPublisher) {
    this.orderRepository = orderRepository;
    this.eventPublisher = eventPublisher;
  }

  @Transactional
  public Order.CancellationResult cancel(UUID orderId, Instant cancelledAt) {
    Order order = orderRepository.findById(orderId)
        .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));
    Order.CancellationResult result = order.cancel(cancelledAt);
    if (result != Order.CancellationResult.CANCELLED) {
      return result;
    }

    orderRepository.save(order);
    eventPublisher.publishAll(order.releaseDomainEvents());
    return result;
  }
}
