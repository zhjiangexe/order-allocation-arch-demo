package com.flowzati.archone.wms.testsupport;

import com.flowzati.archone.wms.dispatch.application.event.ShipmentDispatchStatusChanged;
import com.flowzati.archone.wms.dispatch.application.port.ShipmentDispatchStatusChangedPublisher;
import com.flowzati.archone.wms.dispatch.application.port.ShipmentHandedOverPublisher;
import com.flowzati.archone.wms.dispatch.application.store.ShipmentDispatchStore;
import com.flowzati.archone.wms.picking.application.event.PickingWorkStatusChanged;
import com.flowzati.archone.wms.picking.application.port.PickingWorkStatusChangedPublisher;
import com.flowzati.archone.wms.picking.application.store.PickingWorkStore;
import com.flowzati.archone.wms.process.application.service.WmsDomainEventHandlers;
import com.flowzati.archone.wms.shipment.application.event.ShipmentCancellationCompleted;
import com.flowzati.archone.wms.shipment.application.port.ShipmentCancellationCompletedPublisher;
import com.flowzati.archone.wms.shipment.application.store.ShipmentStore;
import com.flowzati.archone.wms.wave.application.event.WaveReleased;
import com.flowzati.archone.wms.wave.application.port.WaveReleasedPublisher;

public class SynchronousWmsDomainEvents
        implements WaveReleasedPublisher,
                PickingWorkStatusChangedPublisher,
                ShipmentCancellationCompletedPublisher,
                ShipmentDispatchStatusChangedPublisher {

    private final WmsDomainEventHandlers handlers;

    public SynchronousWmsDomainEvents(
            ShipmentStore shipmentStore,
            PickingWorkStore pickingWorkStore,
            ShipmentDispatchStore shipmentDispatchStore,
            ShipmentHandedOverPublisher shipmentHandedOverPublisher) {
        handlers = new WmsDomainEventHandlers(
                shipmentStore, pickingWorkStore, shipmentDispatchStore, shipmentHandedOverPublisher);
    }

    @Override
    public void publish(WaveReleased event) {
        handlers.on(event);
    }

    @Override
    public void publish(PickingWorkStatusChanged event) {
        handlers.on(event);
    }

    @Override
    public void publish(ShipmentCancellationCompleted event) {
        handlers.on(event);
    }

    @Override
    public void publish(ShipmentDispatchStatusChanged event) {
        handlers.on(event);
    }
}
