package com.flowzati.archone.ordering.application.usecase;

import com.flowzati.archone.ordering.application.command.RecordOrderAllocationCommand;
import com.flowzati.archone.ordering.domain.aggregate.Order;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import com.flowzati.archone.ordering.domain.type.OrderStatus;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 將 stock context 已完成的配貨事實記錄到訂單。
 *
 * <p>事件只帶識別碼與時間戳；本 use case 重讀 {@link Order} 並推進狀態。已取消是配貨與取消
 * 競爭下的正常結果，已配置或已履約則代表重複／遲到通知，皆不應落入 DLT。缺貨訂單仍可在
 * 補到貨後推進為已配置。
 */
@Service
public class RecordOrderAllocationUsecase {

    private final OrderRepository orderRepository;

    public RecordOrderAllocationUsecase(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /** Transport-neutral application entrypoint; inbound idempotency belongs to the caller boundary. */
    @Transactional
    public void execute(RecordOrderAllocationCommand command) {
        record(command);
    }

    private void record(RecordOrderAllocationCommand command) {
        Optional<Order> orderOpt = orderRepository.findById(command.orderId());
        if (orderOpt.isEmpty()) {
            return;
        }

        Order order = orderOpt.get();
        if (order.getStatus() == OrderStatus.CANCELLED
                || order.getStatus() == OrderStatus.ALLOCATED
                || order.getStatus() == OrderStatus.FULFILLED) {
            return;
        }

        order.markAllocated(command.allocatedAt());
        orderRepository.save(order);
    }
}
