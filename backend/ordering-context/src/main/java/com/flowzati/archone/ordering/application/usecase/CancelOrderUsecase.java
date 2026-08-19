package com.flowzati.archone.ordering.application.usecase;

import com.flowzati.archone.ordering.application.command.CancelOrderCommand;
import com.flowzati.archone.ordering.application.event.OrderingDomainEventPublisher;
import com.flowzati.archone.ordering.domain.aggregate.Order;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

/**
 * 取消一張訂單。
 *
 * <p>目前由 Temporal 的 {@code CancelOrder} Activity 在 WMS 同意取消後呼叫。未來 REST／操作台
 * 應提交 cancellation request 給 fulfillment coordinator，由它先取得 WMS 決策；不得直接繞過協調
 * 呼叫本 Usecase，否則可能取消 Order 卻留下仍在作業的 Shipment。
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

    private final OrderRepository orderRepository;
    private final OrderingDomainEventPublisher eventPublisher;

    public CancelOrderUsecase(OrderRepository orderRepository, OrderingDomainEventPublisher eventPublisher) {
        this.orderRepository = orderRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public Order.CancellationResult cancel(CancelOrderCommand command) {
        Order order = orderRepository
                .findById(command.orderId())
                .orElseThrow(() -> new IllegalStateException("Order not found: " + command.orderId()));
        Order.CancellationResult result = order.cancel(command.requestId(), command.requestedAt(), command.reason());
        if (result != Order.CancellationResult.CANCELLED) {
            return result;
        }

        orderRepository.save(order);
        eventPublisher.publishAll(order.releaseDomainEvents());
        return result;
    }
}
