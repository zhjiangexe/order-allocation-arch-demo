package com.flowzati.archone.ordering.application.usecase;

import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.common.IdGenerator;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import jakarta.transaction.Transactional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class PlaceOrderUsecase {

  private final OrderRepository orderRepository;
  private final ApplicationEventPublisher publisher;

  public PlaceOrderUsecase(OrderRepository orderRepository, ApplicationEventPublisher publisher) {
    this.orderRepository = orderRepository;
    this.publisher = publisher;
  }

  /**
   * 回傳整個 {@link Order} 而不只是 id，讓 HTTP 邊界能用與單筆查詢相同的表示型別回應下單
   * 結果——客戶端因此只需要一個訂單模型，而不是「建立時拿到一種、查詢時拿到另一種」。
   */
  @Transactional
  public Order placeOrder(String sku, Integer quantity) {
    UUID orderId = IdGenerator.nextId();
    Instant placedAt = Instant.now();
    Order placedOrder = Order.place(orderId, sku, quantity, placedAt);
    orderRepository.save(placedOrder);
    placedOrder.releaseDomainEvents().forEach(publisher::publishEvent);
    return placedOrder;
  }
}
