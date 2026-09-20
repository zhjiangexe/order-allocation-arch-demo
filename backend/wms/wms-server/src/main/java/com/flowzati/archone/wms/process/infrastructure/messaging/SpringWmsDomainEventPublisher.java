package com.flowzati.archone.wms.process.infrastructure.messaging;

import com.flowzati.archone.wms.dispatch.application.event.ShipmentDispatchStatusChanged;
import com.flowzati.archone.wms.dispatch.application.port.ShipmentDispatchStatusChangedPublisher;
import com.flowzati.archone.wms.picking.application.event.PickingWorkStatusChanged;
import com.flowzati.archone.wms.picking.application.port.PickingWorkStatusChangedPublisher;
import com.flowzati.archone.wms.shipment.application.event.ShipmentCancellationCompleted;
import com.flowzati.archone.wms.shipment.application.port.ShipmentCancellationCompletedPublisher;
import com.flowzati.archone.wms.wave.application.event.WaveReleased;
import com.flowzati.archone.wms.wave.application.port.WaveReleasedPublisher;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** 同一 WMS bounded context 內的同步 domain event bus；handler 失敗會讓原交易一起 rollback。 */
@Component
public class SpringWmsDomainEventPublisher
        implements WaveReleasedPublisher,
                PickingWorkStatusChangedPublisher,
                ShipmentCancellationCompletedPublisher,
                ShipmentDispatchStatusChangedPublisher {

    private final ApplicationEventPublisher events;

    public SpringWmsDomainEventPublisher(ApplicationEventPublisher events) {
        this.events = events;
    }

    @Override
    public void publish(WaveReleased event) {
        events.publishEvent(event);
    }

    @Override
    public void publish(PickingWorkStatusChanged event) {
        events.publishEvent(event);
    }

    @Override
    public void publish(ShipmentCancellationCompleted event) {
        events.publishEvent(event);
    }

    @Override
    public void publish(ShipmentDispatchStatusChanged event) {
        events.publishEvent(event);
    }
}
