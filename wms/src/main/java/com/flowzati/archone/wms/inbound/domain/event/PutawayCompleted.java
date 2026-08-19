package com.flowzati.archone.wms.inbound.domain.event;

import com.flowzati.archone.wms.inbound.domain.valueobject.PutawayLine;
import com.flowzati.archone.wms.shared.domain.WmsDomainEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** adapter 可將每筆結果映射成既有 ConfirmStockReceipt command／availability fact。 */
public record PutawayCompleted(
    UUID inboundOperationId,
    UUID ownerId,
    UUID facilityId,
    List<PutawayLine> lines,
    Instant occurredAt
) implements WmsDomainEvent {

  public PutawayCompleted {
    lines = List.copyOf(lines);
  }
}
