package com.flowzati.archone.wms.outbound.domain.event;

import com.flowzati.archone.wms.shared.domain.WmsDomainEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PickingWorkCreated(
    UUID shipmentId,
    UUID waveId,
    UUID warehouseWorkId,
    List<UUID> pickTaskIds,
    Instant occurredAt
) implements WmsDomainEvent {

  public PickingWorkCreated {
    pickTaskIds = List.copyOf(pickTaskIds);
  }
}
