package com.flowzati.archone.ordering.application.usecase;

import com.flowzati.archone.ordering.application.command.RecordOrderBackorderCommand;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 將 stock context 已確認的缺貨事實記錄到訂單。
 *
 * <p>不同 eventId 仍可能描述同一次缺貨，因此已缺貨訂單必須維持第一次記錄的時間。已取消或已配置
 * 的訂單也不再被遲到的缺貨結果推動。
 */
@Service
public class RecordOrderBackorderUsecase {

  private final OrderRepository orderRepository;

  public RecordOrderBackorderUsecase(OrderRepository orderRepository) {
    this.orderRepository = orderRepository;
  }

  /** Transport-neutral application entrypoint; inbound idempotency belongs to the caller boundary. */
  @Transactional
  public void execute(RecordOrderBackorderCommand command) {
    record(command);
  }

  private void record(RecordOrderBackorderCommand command) {
    Optional<Order> orderOpt = orderRepository.findById(command.orderId());
    if (orderOpt.isEmpty()) {
      return;
    }

    Order order = orderOpt.get();
    if (order.getStatus() == OrderStatus.CANCELLED
        || order.getStatus() == OrderStatus.ALLOCATED
        || order.getStatus() == OrderStatus.BACKORDERED) {
      return;
    }

    order.markBackOrdered(command.backorderedAt());
    orderRepository.save(order);
  }
}
