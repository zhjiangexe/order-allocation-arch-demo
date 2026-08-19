package com.flowzati.archone.wms.outbound.domain.event;

import com.flowzati.archone.wms.shared.domain.WmsDomainEvent;
import java.time.Instant;
import java.util.UUID;

/** Shipment 已被一個尚未 release 的 Wave claim，避免重複進入其他 Wave。 */
public record ShipmentAssignedToWave(UUID shipmentId, UUID waveId, Instant occurredAt) implements WmsDomainEvent {}
