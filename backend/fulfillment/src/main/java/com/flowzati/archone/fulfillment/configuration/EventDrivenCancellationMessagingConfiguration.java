package com.flowzati.archone.fulfillment.configuration;

import com.flowzati.archone.contracts.cancel.v1.CancellationEventDestinations;
import com.flowzati.archone.contracts.cancel.v1.OrderingCancellationRequestResolvedIntegrationEvent;
import com.flowzati.archone.contracts.cancel.v1.ShipmentCancelledIntegrationEvent;
import com.flowzati.archone.contracts.cancel.v1.WmsCancellationRequestResolvedIntegrationEvent;
import com.flowzati.archone.foundation.configuration.FulfillmentOrchestrationMode;
import com.flowzati.archone.fulfillment.application.invocation.OrderingCancellationOutcomeCommand;
import com.flowzati.archone.fulfillment.application.invocation.WmsCancellationOutcomeCommand;
import com.flowzati.archone.fulfillment.application.usecase.HandleWmsCancellationOutcomeUsecase;
import com.flowzati.archone.fulfillment.application.usecase.RecordOrderingCancellationOutcomeUsecase;
import com.flowzati.archone.messaging.autoconfigure.ConditionalOnIntegrationEventConsumption;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcher;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventHandlersBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnIntegrationEventConsumption
@ConditionalOnProperty(
        name = FulfillmentOrchestrationMode.ORCHESTRATION_MODE,
        havingValue = FulfillmentOrchestrationMode.EVENTS,
        matchIfMissing = true)
public class EventDrivenCancellationMessagingConfiguration {
    @Bean
    IntegrationEventDispatcher cancellationWmsResultDispatcher(
            IntegrationEventDispatcherFactory factory, HandleWmsCancellationOutcomeUsecase handleWmsOutcome) {
        return factory.make(
                "fulfillment-cancellation-wms-results",
                IntegrationEventHandlersBuilder.forDestination(CancellationEventDestinations.SHIPMENT_EVENTS)
                        .onEvent(ShipmentCancelledIntegrationEvent.class, envelope -> {
                            var event = envelope.event();
                            handleWmsOutcome.handle(new WmsCancellationOutcomeCommand(
                                    event.getCancellationRequestId(),
                                    event.getOrderId(),
                                    event.getCancellationRequestedAt(),
                                    event.getCancellationReason(),
                                    WmsCancellationOutcomeCommand.Outcome.SHIPMENT_CANCELLED));
                        })
                        .onEvent(WmsCancellationRequestResolvedIntegrationEvent.class, envelope -> {
                            var event = envelope.event();
                            handleWmsOutcome.handle(new WmsCancellationOutcomeCommand(
                                    event.getRequestId(),
                                    event.getOrderId(),
                                    event.getRequestedAt(),
                                    event.getReason(),
                                    WmsCancellationOutcomeCommand.Outcome.valueOf(
                                            event.getOutcome().name())));
                        })
                        .build());
    }

    @Bean
    IntegrationEventDispatcher cancellationOrderingResultDispatcher(
            IntegrationEventDispatcherFactory factory, RecordOrderingCancellationOutcomeUsecase recordOrderingOutcome) {
        return factory.make(
                "fulfillment-cancellation-ordering-results",
                IntegrationEventHandlersBuilder.forDestination(
                                CancellationEventDestinations.ORDERING_CANCELLATION_RESULTS)
                        .onEvent(OrderingCancellationRequestResolvedIntegrationEvent.class, envelope -> {
                            var event = envelope.event();
                            recordOrderingOutcome.record(new OrderingCancellationOutcomeCommand(
                                    event.getRequestId(),
                                    event.getOrderId(),
                                    event.getOutcome()
                                                    == OrderingCancellationRequestResolvedIntegrationEvent.Outcome
                                                            .REJECTED
                                            ? OrderingCancellationOutcomeCommand.Outcome.REJECTED
                                            : OrderingCancellationOutcomeCommand.Outcome.SUCCEEDED));
                        })
                        .build());
    }
}
