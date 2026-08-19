package com.flowzati.archone.wms.outbound.infrastructure.messaging.producer;

import com.flowzati.archone.contracts.fulfillment.v1.FulfillmentAggregateTypes;
import com.flowzati.archone.contracts.fulfillment.v1.FulfillmentChannels;
import com.flowzati.archone.contracts.fulfillment.v1.ShipmentHandedOverForFulfillmentIntegrationEvent;
import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.messaging.events.AggregateReference;
import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import com.flowzati.archone.messaging.events.PublicationTarget;
import com.flowzati.archone.wms.outbound.domain.event.ShipmentHandedOverToCarrier;
import com.flowzati.archone.wms.shared.application.DomainEventPublisher;
import com.flowzati.archone.wms.shared.domain.WmsDomainEvent;
import org.springframework.stereotype.Component;

/** 將需要跨 bounded context 的 WMS domain fact 寫入 transactional Outbox。 */
@Component
public class WmsIntegrationEventPublisher implements DomainEventPublisher {

    private final IntegrationEventPublisher eventPublisher;

    public WmsIntegrationEventPublisher(IntegrationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void publish(WmsDomainEvent event) {
        if (event instanceof ShipmentHandedOverToCarrier handedOver) {
            publish(handedOver);
        }
        // 其餘 WMS event 目前只供 aggregate/application 內部觀察；沒有穩定的跨邊界 consumer。
    }

    private void publish(ShipmentHandedOverToCarrier event) {
        ShipmentHandedOverForFulfillmentIntegrationEvent integration =
                new ShipmentHandedOverForFulfillmentIntegrationEvent(
                        IdGenerator.nextId(),
                        event.shipmentId(),
                        event.allocationId(),
                        event.orderId(),
                        event.movementIds(),
                        event.occurredAt());
        eventPublisher.publish(
                integration,
                new AggregateReference(
                        FulfillmentAggregateTypes.WMS_SHIPMENT,
                        event.shipmentId().toString()),
                new PublicationTarget(
                        FulfillmentChannels.FULFILLMENT_HANDOFFS,
                        event.orderId().toString()),
                event.occurredAt());
    }
}
