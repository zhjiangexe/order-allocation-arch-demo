package com.flowzati.archone.wms.picking.application.port;

import com.flowzati.archone.wms.picking.application.event.PickingWorkStatusChanged;

@FunctionalInterface
public interface PickingWorkStatusChangedPublisher {

    void publish(PickingWorkStatusChanged event);
}
