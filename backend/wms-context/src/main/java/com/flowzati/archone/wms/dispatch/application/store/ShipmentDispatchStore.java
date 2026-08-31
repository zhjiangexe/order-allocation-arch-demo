package com.flowzati.archone.wms.dispatch.application.store;

import com.flowzati.archone.wms.dispatch.domain.aggregate.ShipmentDispatch;
import java.util.Optional;
import java.util.UUID;

public interface ShipmentDispatchStore {

    Optional<ShipmentDispatch> findByShipmentId(UUID shipmentId);

    void save(ShipmentDispatch shipmentDispatch);
}
