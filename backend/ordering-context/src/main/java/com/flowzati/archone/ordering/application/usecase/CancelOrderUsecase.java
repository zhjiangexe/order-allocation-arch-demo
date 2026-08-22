package com.flowzati.archone.ordering.application.usecase;

import com.flowzati.archone.contracts.ordering.v1.OrderCancelledIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderingAggregateTypes;
import com.flowzati.archone.contracts.ordering.v1.OrderingChannels;
import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.messaging.events.AggregateReference;
import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import com.flowzati.archone.messaging.events.PublicationTarget;
import com.flowzati.archone.ordering.application.command.CancelOrderCommand;
import com.flowzati.archone.ordering.application.event.OrderingPartitionKeyResolver;
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
    private final IntegrationEventPublisher integrationEventPublisher;
    private final OrderingPartitionKeyResolver partitionKeyResolver;

    public CancelOrderUsecase(
            OrderRepository orderRepository,
            IntegrationEventPublisher integrationEventPublisher,
            OrderingPartitionKeyResolver partitionKeyResolver) {
        this.orderRepository = orderRepository;
        this.integrationEventPublisher = integrationEventPublisher;
        this.partitionKeyResolver = partitionKeyResolver;
    }

    @Transactional
    public Order.CancellationStatus cancel(CancelOrderCommand command) {
        Order order = orderRepository
                .findById(command.orderId())
                .orElseThrow(() -> new IllegalStateException("Order not found: " + command.orderId()));
        Order.CancellationStatus result = order.cancel(command.requestId(), command.requestedAt(), command.reason());
        if (result != Order.CancellationStatus.CANCELLED) {
            return result;
        }

        orderRepository.save(order);
        integrationEventPublisher.publish(
                new OrderCancelledIntegrationEvent(IdGenerator.nextId(), order.getId(), command.requestedAt()),
                new AggregateReference(
                        OrderingAggregateTypes.ORDER, order.getId().toString()),
                new PublicationTarget(
                        OrderingChannels.ORDER_EVENTS,
                        partitionKeyResolver.resolve(
                                order.getId(),
                                order.getOwnerId(),
                                order.getDeliveryTerms().facilityId())),
                command.requestedAt());
        return result;
    }
}
