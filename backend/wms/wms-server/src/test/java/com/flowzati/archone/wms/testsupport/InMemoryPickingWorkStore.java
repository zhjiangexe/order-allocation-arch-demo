package com.flowzati.archone.wms.testsupport;

import com.flowzati.archone.wms.picking.application.store.PickingWorkStore;
import com.flowzati.archone.wms.picking.domain.aggregate.PickingWork;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class InMemoryPickingWorkStore implements PickingWorkStore {

    private final Map<UUID, PickingWork> works = new LinkedHashMap<>();

    @Override
    public Optional<PickingWork> findByShipmentId(UUID shipmentId) {
        return works.values().stream()
                .filter(work -> work.shipmentId().equals(shipmentId))
                .findFirst();
    }

    @Override
    public Optional<PickingWork> findByPickTaskId(UUID pickTaskId) {
        return works.values().stream()
                .filter(work ->
                        work.pickTasks().stream().anyMatch(task -> task.id().equals(pickTaskId)))
                .findFirst();
    }

    @Override
    public void save(PickingWork pickingWork) {
        works.put(pickingWork.id(), pickingWork);
    }
}
