package com.flowzati.archone.wms.dispatch.application.usecase;

import com.flowzati.archone.wms.dispatch.application.event.ShipmentDispatchStatusChanged;
import com.flowzati.archone.wms.dispatch.application.invocation.HandOverShipmentCommand;
import com.flowzati.archone.wms.dispatch.application.port.ShipmentDispatchStatusChangedPublisher;
import com.flowzati.archone.wms.dispatch.application.store.ShipmentDispatchStore;
import com.flowzati.archone.wms.dispatch.domain.aggregate.ShipmentDispatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 完成 WMS 對承運人的 custody handover；運輸離站後續由 TMS 負責。 */
@Service
public class HandOverShipmentUsecase {

    private static final Logger log = LoggerFactory.getLogger(HandOverShipmentUsecase.class);

    private final ShipmentDispatchStore shipmentDispatchStore;
    private final ShipmentDispatchStatusChangedPublisher shipmentDispatchStatusChangedPublisher;

    public HandOverShipmentUsecase(
            ShipmentDispatchStore shipmentDispatchStore,
            ShipmentDispatchStatusChangedPublisher shipmentDispatchStatusChangedPublisher) {
        this.shipmentDispatchStore = shipmentDispatchStore;
        this.shipmentDispatchStatusChangedPublisher = shipmentDispatchStatusChangedPublisher;
    }

    @Transactional
    public void handle(HandOverShipmentCommand command) {
        ShipmentDispatch shipmentDispatch = shipmentDispatchStore
                .findByShipmentId(command.shipmentId())
                .orElseThrow(() -> new IllegalStateException("ShipmentDispatch not found: " + command.shipmentId()));
        if (!shipmentDispatch.handOver(command.handedOverAt())) {
            return;
        }
        shipmentDispatchStore.save(shipmentDispatch);
        shipmentDispatchStatusChangedPublisher.publish(new ShipmentDispatchStatusChanged(
                shipmentDispatch.id(),
                shipmentDispatch.shipmentId(),
                shipmentDispatch.status(),
                command.handedOverAt()));
        log.info(
                "WMS shipment dispatch handed over: shipmentDispatchId={}, shipmentId={}, status={}",
                shipmentDispatch.id(),
                shipmentDispatch.shipmentId(),
                shipmentDispatch.status());
    }
}
