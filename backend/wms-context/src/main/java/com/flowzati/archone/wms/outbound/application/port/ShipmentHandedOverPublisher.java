package com.flowzati.archone.wms.outbound.application.port;

import com.flowzati.archone.wms.outbound.application.event.ShipmentHandedOver;

/** Publishes the completed WMS-to-carrier custody transfer. */
@FunctionalInterface
public interface ShipmentHandedOverPublisher {

    void publish(ShipmentHandedOver event);
}
