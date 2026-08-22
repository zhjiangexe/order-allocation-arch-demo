package com.flowzati.archone.wms.outbound.entrypoint.messaging;

import com.flowzati.archone.contracts.promising.v1.AllocationChannels;
import com.flowzati.archone.contracts.promising.v1.OrderAllocationCommittedIntegrationEvent;
import com.flowzati.archone.messaging.autoconfigure.ConditionalOnIntegrationEventConsumption;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcher;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventHandlers;
import com.flowzati.archone.messaging.events.IntegrationEventHandlersBuilder;
import com.flowzati.archone.wms.outbound.application.command.CreateShipmentCommand;
import com.flowzati.archone.wms.outbound.application.usecase.CreateShipmentUsecase;
import com.flowzati.archone.wms.shared.application.IdGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Tram-style fulfillment handoff consumer: Inbox transaction surrounds this complete handler. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnIntegrationEventConsumption
@ConditionalOnProperty(name = "archone.fulfillment.orchestration-mode", havingValue = "events", matchIfMissing = true)
public class WmsFulfillmentHandoffEventConsumer {

    private final CreateShipmentUsecase createShipmentUsecase;
    private final IdGenerator idGenerator;

    public WmsFulfillmentHandoffEventConsumer(CreateShipmentUsecase createShipmentUsecase, IdGenerator idGenerator) {
        this.createShipmentUsecase = createShipmentUsecase;
        this.idGenerator = idGenerator;
    }

    @Bean
    IntegrationEventDispatcher wmsFulfillmentHandoffIntegrationEventDispatcher(
            IntegrationEventDispatcherFactory factory) {
        IntegrationEventHandlers handlers = IntegrationEventHandlersBuilder.forDestination(
                        AllocationChannels.ALLOCATION_EVENTS)
                .onEvent(
                        OrderAllocationCommittedIntegrationEvent.class,
                        envelope -> onAllocationCommitted(envelope.event()))
                .build();
        return factory.make(WmsEventSubscriptions.FULFILLMENT_HANDOFF, handlers);
    }

    void onAllocationCommitted(OrderAllocationCommittedIntegrationEvent event) {
        // Event-driven driver 不需要同步回覆；CreateShipmentResult 仍確保相同 application use case
        // 也能被 Temporal Activity adapter 使用，而不必回傳 domain Shipment aggregate。
        createShipmentUsecase.handle(new CreateShipmentCommand(
                idGenerator.nextId(),
                event.getAllocationId(),
                event.getOrderId(),
                event.getOwnerId(),
                event.getFacilityId(),
                event.getLines().stream()
                        .map(line -> new CreateShipmentCommand.AllocationLine(
                                line.orderLineId(),
                                line.moveId(),
                                line.skuCode(),
                                line.sourceLocationId(),
                                line.quantity()))
                        .toList(),
                event.getDispatchBy(),
                event.getReleasePriority(),
                event.getCommittedAt()));
    }
}
