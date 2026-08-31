package com.flowzati.archone.wms.picking.application.store;

import com.flowzati.archone.wms.picking.domain.aggregate.PickingWork;
import java.util.Optional;
import java.util.UUID;

public interface PickingWorkStore {

    Optional<PickingWork> findByShipmentId(UUID shipmentId);

    Optional<PickingWork> findByPickTaskId(UUID pickTaskId);

    void save(PickingWork pickingWork);
}
