package com.flowzati.archone.wms.wave.application.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Wave 已凍結且 release 成功；下游可依 assignments 建立不可再變動的 PickingWork。 */
public record WaveReleased(UUID waveId, List<UUID> shipmentIds, Instant releasedAt) {

    public WaveReleased {
        if (waveId == null || shipmentIds == null || shipmentIds.isEmpty() || releasedAt == null) {
            throw new IllegalArgumentException("WaveReleased requires wave, shipments and release time");
        }
        shipmentIds = List.copyOf(shipmentIds);
    }
}
