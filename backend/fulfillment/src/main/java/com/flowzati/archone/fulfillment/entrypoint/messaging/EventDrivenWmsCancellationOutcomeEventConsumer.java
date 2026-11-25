package com.flowzati.archone.fulfillment.entrypoint.messaging;

import com.flowzati.archone.contracts.cancel.v1.CancellationEventDestinations;
import com.flowzati.archone.contracts.cancel.v1.ShipmentCancelledIntegrationEvent;
import com.flowzati.archone.contracts.cancel.v1.WmsCancellationRequestResolvedIntegrationEvent;
import com.flowzati.archone.foundation.configuration.FulfillmentOrchestrationMode;
import com.flowzati.archone.fulfillment.application.enums.WmsCancellationOutcome;
import com.flowzati.archone.fulfillment.application.invocation.WmsCancellationOutcomeCommand;
import com.flowzati.archone.fulfillment.application.usecase.HandleWmsCancellationOutcomeUsecase;
import com.flowzati.archone.messaging.autoconfigure.ConditionalOnIntegrationEventConsumption;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcher;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventHandlersBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Events 模式：WMS 回報的取消結果（出貨單已取消，或取消請求已處理）推進取消流程。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnIntegrationEventConsumption
@ConditionalOnProperty(
        name = FulfillmentOrchestrationMode.ORCHESTRATION_MODE,
        havingValue = FulfillmentOrchestrationMode.EVENTS,
        matchIfMissing = true)
public class EventDrivenWmsCancellationOutcomeEventConsumer {

    private final HandleWmsCancellationOutcomeUsecase usecase;

    public EventDrivenWmsCancellationOutcomeEventConsumer(HandleWmsCancellationOutcomeUsecase usecase) {
        this.usecase = usecase;
    }

    @Bean
    IntegrationEventDispatcher cancellationWmsResultDispatcher(IntegrationEventDispatcherFactory factory) {
        return factory.make(
                FulfillmentEventSubscriptions.CANCELLATION_WMS_RESULTS,
                IntegrationEventHandlersBuilder.forDestination(CancellationEventDestinations.SHIPMENT_EVENTS)
                        .onEvent(
                                ShipmentCancelledIntegrationEvent.class,
                                envelope -> onShipmentCancelled(envelope.event()))
                        .onEvent(
                                WmsCancellationRequestResolvedIntegrationEvent.class,
                                envelope -> onCancellationRequestResolved(envelope.event()))
                        .build());
    }

    void onShipmentCancelled(ShipmentCancelledIntegrationEvent event) {
        usecase.handle(new WmsCancellationOutcomeCommand(
                event.getCancellationRequestId(),
                event.getOrderId(),
                event.getCancellationRequestedAt(),
                event.getCancellationReason(),
                WmsCancellationOutcome.SHIPMENT_CANCELLED));
    }

    void onCancellationRequestResolved(WmsCancellationRequestResolvedIntegrationEvent event) {
        usecase.handle(new WmsCancellationOutcomeCommand(
                event.getRequestId(),
                event.getOrderId(),
                event.getRequestedAt(),
                event.getReason(),
                WmsCancellationOutcome.valueOf(event.getOutcome().name())));
    }
}
