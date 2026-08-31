package com.flowzati.archone.ordering.application.usecase;

import com.flowzati.archone.ordering.application.invocation.RecordOrderFulfillmentCommand;
import com.flowzati.archone.ordering.application.store.OrderStore;
import com.flowzati.archone.ordering.domain.aggregate.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在出庫 movements 完成後，將 Ordering aggregate 冪等推進到 FULFILLED。
 *
 * <p>只有 Shipment ID 與完成時間都相同的 FULFILLED 事實是 no-op。另一張 Shipment 或不同 payload
 * 會被視為衝突；PENDING 或 CANCELLED 收到完成通知則代表跨邊界順序／補償出錯。這些情況都交由
 * domain invariant 明確失敗，不能安靜吞掉實體貨物已離倉的事實。
 */
@Service
public class RecordOrderFulfillmentUsecase {

    private final OrderStore orderStore;

    public RecordOrderFulfillmentUsecase(OrderStore orderStore) {
        this.orderStore = orderStore;
    }

    /** Transport-neutral entrypoint，可由 Kafka consumer 或 Temporal Activity adapter 共用。 */
    @Transactional
    public void execute(RecordOrderFulfillmentCommand command) {
        Order order = orderStore
                .findById(command.orderId())
                .orElseThrow(() -> new IllegalStateException("Order not found: " + command.orderId()));
        if (!order.markFulfilled(command.shipmentId(), command.fulfilledAt())) {
            return;
        }
        orderStore.save(order);
    }
}
