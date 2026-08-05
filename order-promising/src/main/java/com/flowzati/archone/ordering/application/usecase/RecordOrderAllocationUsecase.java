package com.flowzati.archone.ordering.application.usecase;

import com.flowzati.archone.common.inbox.InboundCommand;
import com.flowzati.archone.common.inbox.InboxRepo;
import com.flowzati.archone.ordering.application.command.RecordOrderAllocationCommand;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 將 stock context 已完成的配貨事實記錄到訂單。
 *
 * <p>事件只帶識別碼與時間戳；本 use case 重讀 {@link Order} 並推進狀態。已取消是配貨與取消
 * 競爭下的正常結果，已配置則代表重複通知，兩者都不應落入 DLT。缺貨訂單仍可在補到貨後推進為
 * 已配置。
 */
@Service
public class RecordOrderAllocationUsecase {

  private final OrderRepository orderRepository;
  private final InboxRepo inboxRepo;

  public RecordOrderAllocationUsecase(OrderRepository orderRepository, InboxRepo inboxRepo) {
    this.orderRepository = orderRepository;
    this.inboxRepo = inboxRepo;
  }

  @Transactional
  public void handle(InboundCommand<RecordOrderAllocationCommand> inbound) {
    if (!inboxRepo.claimIfNew(inbound.message())) {
      return;
    }

    RecordOrderAllocationCommand command = inbound.command();
    Optional<Order> orderOpt = orderRepository.findById(command.orderId());
    if (orderOpt.isEmpty()) {
      return;
    }

    Order order = orderOpt.get();
    if (order.getStatus() == OrderStatus.CANCELLED
        || order.getStatus() == OrderStatus.ALLOCATED) {
      return;
    }

    order.markAllocated(command.allocatedAt());
    orderRepository.save(order);
  }
}
