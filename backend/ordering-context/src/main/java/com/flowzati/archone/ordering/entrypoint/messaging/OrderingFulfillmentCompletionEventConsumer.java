package com.flowzati.archone.ordering.entrypoint.messaging;

import com.flowzati.archone.contracts.fulfillment.v1.FulfillmentChannels;
import com.flowzati.archone.contracts.fulfillment.v1.OutboundMovementsCompletedForFulfillmentIntegrationEvent;
import com.flowzati.archone.messaging.autoconfigure.ConditionalOnIntegrationEventConsumption;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcher;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventHandlers;
import com.flowzati.archone.messaging.events.IntegrationEventHandlersBuilder;
import com.flowzati.archone.ordering.application.command.RecordOrderFulfillmentCommand;
import com.flowzati.archone.ordering.application.event.OrderingEventSubscriptions;
import com.flowzati.archone.ordering.application.usecase.RecordOrderFulfillmentUsecase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Event-driven 模式下，Inventory 出庫完成後將 Ordering 推進到 FULFILLED。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnIntegrationEventConsumption
@ConditionalOnProperty(name = "archone.fulfillment.orchestration-mode", havingValue = "events", matchIfMissing = true)
public class OrderingFulfillmentCompletionEventConsumer {

    private final RecordOrderFulfillmentUsecase recordOrderFulfillmentUsecase;

    public OrderingFulfillmentCompletionEventConsumer(RecordOrderFulfillmentUsecase recordOrderFulfillmentUsecase) {
        this.recordOrderFulfillmentUsecase = recordOrderFulfillmentUsecase;
    }

    @Bean
    IntegrationEventDispatcher orderingFulfillmentCompletionIntegrationEventDispatcher(
            IntegrationEventDispatcherFactory factory) {
        IntegrationEventHandlers handlers = IntegrationEventHandlersBuilder.forDestination(
                        FulfillmentChannels.FULFILLMENT_HANDOFFS)
                .onEvent(
                        OutboundMovementsCompletedForFulfillmentIntegrationEvent.class,
                        envelope -> onOutboundMovementsCompleted(envelope.event()))
                .build();
        return factory.make(OrderingEventSubscriptions.FULFILLMENT_COMPLETION, handlers);
    }

    void onOutboundMovementsCompleted(OutboundMovementsCompletedForFulfillmentIntegrationEvent event) {
        recordOrderFulfillmentUsecase.execute(
                new RecordOrderFulfillmentCommand(event.getOrderId(), event.getCompletedAt()));
    }
}
