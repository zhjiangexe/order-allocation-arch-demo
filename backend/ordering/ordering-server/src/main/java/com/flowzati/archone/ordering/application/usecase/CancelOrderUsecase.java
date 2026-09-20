package com.flowzati.archone.ordering.application.usecase;

import com.flowzati.archone.ordering.application.event.OrderCancelled;
import com.flowzati.archone.ordering.application.invocation.CancelOrderCommand;
import com.flowzati.archone.ordering.application.port.OrderCancelledPublisher;
import com.flowzati.archone.ordering.application.store.OrderStore;
import com.flowzati.archone.ordering.domain.aggregate.Order;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

/**
 * 取消一張訂單。
 *
 * <p>尚未建立 Shipment，或 WMS 已完成取消後，由 fulfillment coordinator／consumer 呼叫。
 * WMS 已取消後若回傳 REJECTED，由呼叫端判定為跨系統狀態矛盾。
 * REST／操作台應經由 fulfillment coordinator 提交請求，不得繞過協調直接取消 Order。
 *
 * <p>{@link CancelOrderCommand#requestId()} 與 immutable payload 會保存於 Order。完全相同的重播是
 * no-op；同一張 Order 收到另一筆 request 則明確衝突，避免「終態剛好相同」被誤認成同一件事。
 *
 * <p><b>查無訂單時拋 {@link IllegalStateException}，而它該被映射成什麼由 entrypoint 決定</b>，
 * 不是這一層：HTTP 入口該回 404 而不是 500；Kafka 入口則反而該讓它落 DLT——上游取消一張我們沒
 * 收到的單，靜默忽略等於丟掉一個訊號。同一個例外在兩種入口下需要相反的處置，所以這裡只負責
 * 誠實地拋。
 */
@Service
public class CancelOrderUsecase {

    private final OrderStore orderStore;
    private final OrderCancelledPublisher orderCancelledPublisher;

    public CancelOrderUsecase(OrderStore orderStore, OrderCancelledPublisher orderCancelledPublisher) {
        this.orderStore = orderStore;
        this.orderCancelledPublisher = orderCancelledPublisher;
    }

    @Transactional
    public Order.CancellationStatus cancel(CancelOrderCommand command) {
        Order order = orderStore
                .findById(command.orderId())
                .orElseThrow(() -> new IllegalStateException("Order not found: " + command.orderId()));
        Order.CancellationStatus result = order.cancel(command.requestId(), command.cancelledAt(), command.reason());
        if (result != Order.CancellationStatus.CANCELLED) {
            return result;
        }

        orderStore.save(order);
        orderCancelledPublisher.publish(new OrderCancelled(
                order.getId(), order.getOwnerId(), order.getDeliveryTerms().facilityId(), command.cancelledAt()));
        return result;
    }
}
