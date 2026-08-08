package com.flowzati.archone.wms.outbound.wave.application.command;

import java.time.Instant;
import java.util.UUID;

/**
 * 建立一個有界、可解釋的 picking Wave。
 *
 * <p>Cutoff 與容量屬 WMS Wave template／營運輸入，不由 order-promising 決定。
 * Shipment 的 promised dispatch time 與 release priority 則必須先由 order-promising integration
 * snapshot 提供，見 {@code CreateShipmentCommand} 上的欄位註解。
 */
public record PlanWaveCommand(
    UUID waveId,
    UUID facilityId,
    String templateCode,
    Instant dispatchByCutoff,
    int candidateScanLimit,
    int maxShipments,
    int maxLines,
    int maxUnits,
    Instant plannedAt
) {

  public PlanWaveCommand {
    if (waveId == null || facilityId == null || dispatchByCutoff == null || plannedAt == null) {
      throw new IllegalArgumentException("Plan Wave requires IDs and time fields");
    }
    if (templateCode == null || templateCode.isBlank()) {
      throw new IllegalArgumentException("Plan Wave requires template code");
    }
    if (candidateScanLimit < maxShipments
        || maxShipments <= 0 || maxLines <= 0 || maxUnits <= 0) {
      throw new IllegalArgumentException(
          "Plan Wave capacities must be positive and scan limit must cover max Shipments");
    }
  }
}
