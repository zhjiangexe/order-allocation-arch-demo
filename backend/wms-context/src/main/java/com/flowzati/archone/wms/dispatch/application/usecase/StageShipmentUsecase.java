package com.flowzati.archone.wms.dispatch.application.usecase;

import com.flowzati.archone.wms.dispatch.application.event.ShipmentDispatchStatusChanged;
import com.flowzati.archone.wms.dispatch.application.invocation.StageShipmentCommand;
import com.flowzati.archone.wms.dispatch.application.port.ShipmentDispatchStatusChangedPublisher;
import com.flowzati.archone.wms.dispatch.application.store.ShipmentDispatchStore;
import com.flowzati.archone.wms.dispatch.domain.aggregate.ShipmentDispatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

public class StageShipmentUsecase {

    private static final Logger log = LoggerFactory.getLogger(StageShipmentUsecase.class);

    private final ShipmentDispatchStore shipmentDispatchStore;
    private final ShipmentDispatchStatusChangedPublisher shipmentDispatchStatusChangedPublisher;

    public StageShipmentUsecase(
            ShipmentDispatchStore shipmentDispatchStore,
            ShipmentDispatchStatusChangedPublisher shipmentDispatchStatusChangedPublisher) {
        this.shipmentDispatchStore = shipmentDispatchStore;
        this.shipmentDispatchStatusChangedPublisher = shipmentDispatchStatusChangedPublisher;
    }

    @Transactional
    public void handle(StageShipmentCommand command) {
        ShipmentDispatch shipmentDispatch = shipmentDispatchStore
                .findByShipmentId(command.shipmentId())
                .orElseThrow(() -> new IllegalStateException("ShipmentDispatch not found: " + command.shipmentId()));
        if (!shipmentDispatch.stage(command.stagedAt())) {
            return;
        }
        shipmentDispatchStore.save(shipmentDispatch);
        shipmentDispatchStatusChangedPublisher.publish(new ShipmentDispatchStatusChanged(
                shipmentDispatch.id(), shipmentDispatch.shipmentId(), shipmentDispatch.status(), command.stagedAt()));
        log.info(
                "WMS shipment dispatch staged: shipmentDispatchId={}, shipmentId={}",
                shipmentDispatch.id(),
                shipmentDispatch.shipmentId());
    }
}
