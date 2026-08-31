package com.flowzati.archone.wms.shipment.application.port;

import com.flowzati.archone.wms.shipment.application.event.ShipmentCancellationCompleted;

@FunctionalInterface
public interface ShipmentCancellationCompletedPublisher {

    void publish(ShipmentCancellationCompleted event);
}
