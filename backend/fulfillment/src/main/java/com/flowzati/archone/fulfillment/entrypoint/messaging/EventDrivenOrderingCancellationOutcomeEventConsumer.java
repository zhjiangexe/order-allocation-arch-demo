package com.flowzati.archone.fulfillment.entrypoint.messaging;

import com.flowzati.archone.contracts.cancel.v1.CancellationEventDestinations;
import com.flowzati.archone.contracts.cancel.v1.OrderingCancellationRequestResolvedIntegrationEvent;
import com.flowzati.archone.foundation.configuration.FulfillmentOrchestrationMode;
import com.flowzati.archone.fulfillment.application.enums.OrderingCancellationOutcome;
import com.flowzati.archone.fulfillment.application.invocation.OrderingCancellationOutcomeCommand;
import com.flowzati.archone.fulfillment.application.usecase.RecordOrderingCancellationOutcomeUsecase;
import com.flowzati.archone.messaging.autoconfigure.ConditionalOnIntegrationEventConsumption;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcher;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventHandlersBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Events 模式：Ordering 回報的取消結果推進取消流程。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnIntegrationEventConsumption
@ConditionalOnProperty(
        name = FulfillmentOrchestrationMode.ORCHESTRATION_MODE,
        havingValue = FulfillmentOrchestrationMode.EVENTS,
        matchIfMissing = true)
public class EventDrivenOrderingCancellationOutcomeEventConsumer {

    private final RecordOrderingCancellationOutcomeUsecase usecase;

    public EventDrivenOrderingCancellationOutcomeEventConsumer(RecordOrderingCancellationOutcomeUsecase usecase) {
        this.usecase = usecase;
    }

    @Bean
    IntegrationEventDispatcher cancellationOrderingResultDispatcher(IntegrationEventDispatcherFactory factory) {
        return factory.make(
                FulfillmentEventSubscriptions.CANCELLATION_ORDERING_RESULTS,
                IntegrationEventHandlersBuilder.forDestination(
                                CancellationEventDestinations.ORDERING_CANCELLATION_RESULTS)
                        .onEvent(
                                OrderingCancellationRequestResolvedIntegrationEvent.class,
                                envelope -> onCancellationRequestResolved(envelope.event()))
                        .build());
    }

    void onCancellationRequestResolved(OrderingCancellationRequestResolvedIntegrationEvent event) {
        usecase.record(new OrderingCancellationOutcomeCommand(
                event.getRequestId(),
                event.getOrderId(),
                event.getOutcome() == OrderingCancellationRequestResolvedIntegrationEvent.Outcome.REJECTED
                        ? OrderingCancellationOutcome.REJECTED
                        : OrderingCancellationOutcome.SUCCEEDED));
    }
}
