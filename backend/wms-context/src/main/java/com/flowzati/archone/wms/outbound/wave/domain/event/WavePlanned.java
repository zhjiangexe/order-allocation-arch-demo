package com.flowzati.archone.wms.outbound.wave.domain.event;

import com.flowzati.archone.wms.shared.domain.WmsDomainEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WavePlanned(
        UUID waveId,
        UUID facilityId,
        String templateCode,
        List<UUID> shipmentIds,
        int totalLines,
        int totalUnits,
        Instant occurredAt)
        implements WmsDomainEvent {

    public WavePlanned {
        shipmentIds = List.copyOf(shipmentIds);
    }
}
