package com.flowzati.archone.wms.dispatch.application.port;

import com.flowzati.archone.wms.dispatch.application.event.ShipmentDispatchStatusChanged;

@FunctionalInterface
public interface ShipmentDispatchStatusChangedPublisher {

    void publish(ShipmentDispatchStatusChanged event);
}
