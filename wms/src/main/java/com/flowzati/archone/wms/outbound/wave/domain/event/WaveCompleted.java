package com.flowzati.archone.wms.outbound.wave.domain.event;

import com.flowzati.archone.wms.shared.domain.WmsDomainEvent;
import java.time.Instant;
import java.util.UUID;

public record WaveCompleted(UUID waveId, UUID facilityId, Instant occurredAt)
    implements WmsDomainEvent {
}
