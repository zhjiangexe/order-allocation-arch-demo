package com.flowzati.archone.wms.testsupport;

import com.flowzati.archone.wms.dispatch.application.store.ShipmentDispatchStore;
import com.flowzati.archone.wms.dispatch.domain.aggregate.ShipmentDispatch;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class InMemoryShipmentDispatchStore implements ShipmentDispatchStore {

    private final Map<UUID, ShipmentDispatch> shipmentDispatches = new LinkedHashMap<>();

    @Override
    public Optional<ShipmentDispatch> findByShipmentId(UUID shipmentId) {
        return shipmentDispatches.values().stream()
                .filter(shipmentDispatch -> shipmentDispatch.shipmentId().equals(shipmentId))
                .findFirst();
    }

    @Override
    public void save(ShipmentDispatch shipmentDispatch) {
        shipmentDispatches.put(shipmentDispatch.id(), shipmentDispatch);
    }
}
