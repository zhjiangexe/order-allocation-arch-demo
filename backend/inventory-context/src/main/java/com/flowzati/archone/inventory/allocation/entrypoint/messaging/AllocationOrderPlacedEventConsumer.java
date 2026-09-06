package com.flowzati.archone.inventory.allocation.entrypoint.messaging;

import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderingChannels;
import com.flowzati.archone.inventory.allocation.application.invocation.AllocateOrderCommand;
import com.flowzati.archone.inventory.allocation.application.usecase.AllocateOrderUsecase;
import com.flowzati.archone.inventory.allocation.entrypoint.ReservationIntakeEventSubscriptions;
import com.flowzati.archone.messaging.autoconfigure.ConditionalOnIntegrationEventConsumption;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcher;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventHandlers;
import com.flowzati.archone.messaging.events.IntegrationEventHandlersBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Event-driven 模式的 order-placement driver；Temporal 模式由同一 subscriber identity 接手。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnIntegrationEventConsumption
@ConditionalOnProperty(name = "archone.fulfillment.orchestration-mode", havingValue = "events", matchIfMissing = true)
public class AllocationOrderPlacedEventConsumer {

    private final AllocateOrderUsecase allocateOrderUsecase;

    public AllocationOrderPlacedEventConsumer(AllocateOrderUsecase allocateOrderUsecase) {
        this.allocateOrderUsecase = allocateOrderUsecase;
    }

    @Bean
    IntegrationEventDispatcher allocationOrderPlacedIntegrationEventDispatcher(
            IntegrationEventDispatcherFactory factory) {
        IntegrationEventHandlers handlers = IntegrationEventHandlersBuilder.forDestination(
                        OrderingChannels.ORDER_EVENTS)
                .onEvent(OrderPlacedIntegrationEvent.class, envelope -> onOrderPlaced(envelope.event()))
                .build();
        return factory.make(ReservationIntakeEventSubscriptions.ORDER_PLACEMENT_DRIVER, handlers);
    }

    void onOrderPlaced(OrderPlacedIntegrationEvent event) {
        // 訊息層已處理 Inbox 冪等性；Inventory 入口只接收最小的 orderId。
        allocateOrderUsecase.execute(new AllocateOrderCommand(event.getOrderId()));
    }
}
