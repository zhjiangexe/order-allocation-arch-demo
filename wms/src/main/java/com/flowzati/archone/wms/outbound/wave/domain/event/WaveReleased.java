package com.flowzati.archone.wms.outbound.wave.domain.event;

import com.flowzati.archone.wms.shared.domain.WmsDomainEvent;
import java.time.Instant;
import java.util.UUID;

public record WaveReleased(
    UUID waveId,
    UUID facilityId,
    int shipmentCount,
    int warehouseWorkCount,
    int pickTaskCount,
    Instant occurredAt
) implements WmsDomainEvent {
}
