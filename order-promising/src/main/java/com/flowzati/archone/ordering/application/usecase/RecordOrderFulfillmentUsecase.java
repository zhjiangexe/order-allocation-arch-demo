package com.flowzati.archone.ordering.application.usecase;

import com.flowzati.archone.ordering.application.command.RecordOrderFulfillmentCommand;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在出庫 movements 完成後，將 Ordering aggregate 冪等推進到 FULFILLED。
 *
 * <p>只有重複的 FULFILLED 通知是 no-op。PENDING 或 CANCELLED 收到完成通知代表
 * 跨邊界順序／補償出錯，交由 domain invariant 明確失敗，不能安靜吞掉實體貨物已離倉的事實。
 */
@Service
public class RecordOrderFulfillmentUsecase {

  private final OrderRepository orderRepository;

  public RecordOrderFulfillmentUsecase(OrderRepository orderRepository) {
    this.orderRepository = orderRepository;
  }

  /** Transport-neutral entrypoint，可由 Kafka consumer 或 Temporal Activity adapter 共用。 */
  @Transactional
  public void execute(RecordOrderFulfillmentCommand command) {
    Order order = orderRepository.findById(command.orderId())
        .orElseThrow(() -> new IllegalStateException("Order not found: " + command.orderId()));
    if (order.getStatus() == OrderStatus.FULFILLED) {
      return;
    }

    order.markFulfilled(command.fulfilledAt());
    orderRepository.save(order);
  }
}
