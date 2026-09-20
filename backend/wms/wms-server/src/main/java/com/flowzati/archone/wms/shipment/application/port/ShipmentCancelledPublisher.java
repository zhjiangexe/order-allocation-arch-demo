package com.flowzati.archone.wms.shipment.application.port;

import com.flowzati.archone.wms.shipment.application.event.ShipmentCancelled;

/** Publishes the completed WMS Shipment cancellation fact. */
@FunctionalInterface
public interface ShipmentCancelledPublisher {

    void publish(ShipmentCancelled event);
}
