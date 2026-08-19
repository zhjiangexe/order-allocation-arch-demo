package com.flowzati.archone.wms.outbound.wave.domain.valueobject;

import com.flowzati.archone.wms.outbound.domain.aggregate.Shipment;
import java.time.Instant;
import java.util.UUID;

/** Wave planner 使用的不可變 Shipment 摘要，避免 planning policy 直接修改 Shipment aggregate。 */
public record WaveCandidate(
    UUID shipmentId,
    UUID facilityId,
    Instant dispatchBy,
    int releasePriority,
    Instant createdAt,
    int lineCount,
    int unitCount
) {

  public WaveCandidate {
    if (shipmentId == null || facilityId == null || dispatchBy == null || createdAt == null) {
      throw new IllegalArgumentException("Wave candidate requires shipment, facility and time fields");
    }
    if (releasePriority < 0 || releasePriority > 100) {
      throw new IllegalArgumentException("Release priority must be between 0 and 100");
    }
    if (lineCount <= 0 || unitCount <= 0) {
      throw new IllegalArgumentException("Wave candidate requires positive line and unit counts");
    }
  }

  public static WaveCandidate from(Shipment shipment) {
    return new WaveCandidate(
        shipment.id(),
        shipment.facilityId(),
        shipment.dispatchBy(),
        shipment.releasePriority(),
        shipment.createdAt(),
        shipment.lines().size(),
        shipment.lines().stream()
            .map(line -> line.quantity())
            .reduce(0, Math::addExact));
  }
}
