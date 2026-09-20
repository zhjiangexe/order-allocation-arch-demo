package com.flowzati.archone.wms.shipment.application.port;

import com.flowzati.archone.wms.shipment.application.event.OrderShipmentCancellationResolved;

public interface OrderShipmentCancellationResolvedPublisher {
    void publish(OrderShipmentCancellationResolved event);
}
