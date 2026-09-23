package com.flowzati.archone.wms.shipment.entrypoint.messaging;

import com.flowzati.archone.contracts.cancel.v1.CancellationEventDestinations;
import com.flowzati.archone.contracts.cancel.v1.WmsCancellationRequestedIntegrationEvent;
import com.flowzati.archone.foundation.configuration.FulfillmentOrchestrationMode;
import com.flowzati.archone.messaging.autoconfigure.ConditionalOnIntegrationEventConsumption;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcher;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventHandlers;
import com.flowzati.archone.messaging.events.IntegrationEventHandlersBuilder;
import com.flowzati.archone.wms.shipment.application.invocation.RequestOrderShipmentCancellationCommand;
import com.flowzati.archone.wms.shipment.application.usecase.RequestOrderShipmentCancellationUsecase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnIntegrationEventConsumption
@ConditionalOnProperty(
        name = FulfillmentOrchestrationMode.ORCHESTRATION_MODE,
        havingValue = FulfillmentOrchestrationMode.EVENTS,
        matchIfMissing = true)
public class WmsOrderCancellationRequestEventConsumer {

    private final RequestOrderShipmentCancellationUsecase requestCancellation;

    public WmsOrderCancellationRequestEventConsumer(RequestOrderShipmentCancellationUsecase requestCancellation) {
        this.requestCancellation = requestCancellation;
    }

    @Bean
    IntegrationEventDispatcher wmsOrderCancellationRequestDispatcher(IntegrationEventDispatcherFactory factory) {
        IntegrationEventHandlers handlers = IntegrationEventHandlersBuilder.forDestination(
                        CancellationEventDestinations.CANCELLATION_REQUESTS)
                .onEvent(WmsCancellationRequestedIntegrationEvent.class, envelope -> {
                    WmsCancellationRequestedIntegrationEvent event = envelope.event();
                    requestCancellation.request(new RequestOrderShipmentCancellationCommand(
                            event.getRequestId(), event.getOrderId(), event.getRequestedAt(), event.getReason()));
                })
                .build();
        return factory.make(WmsEventSubscriptions.ORDER_CANCELLATION_REQUESTS, handlers);
    }
}
