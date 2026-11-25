package com.flowzati.archone.wms.process.application.invocation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 一個波次已釋放：為其中每張出貨單建立揀貨工作。 */
public record ReleaseShipmentsToPickingCommand(UUID waveId, List<UUID> shipmentIds, Instant releasedAt) {

    public ReleaseShipmentsToPickingCommand {
        if (waveId == null || shipmentIds == null || releasedAt == null) {
            throw new IllegalArgumentException("Wave, shipments and release time are required");
        }
        shipmentIds = List.copyOf(shipmentIds);
    }
}
