package com.flowzati.archone.wms.outbound.wave.domain.policy;

import com.flowzati.archone.wms.outbound.wave.domain.valueobject.WaveCandidate;

import java.time.Instant;
import java.util.UUID;

/** 第一版 Wave template snapshot：限定 Facility、dispatch cutoff 與三種可解釋的容量。 */
public record WavePlanningPolicy(
    UUID facilityId,
    Instant dispatchByCutoff,
    int maxShipments,
    int maxLines,
    int maxUnits
) {

  public WavePlanningPolicy {
    if (facilityId == null || dispatchByCutoff == null) {
      throw new IllegalArgumentException("Wave policy requires facility and dispatch cutoff");
    }
    if (maxShipments <= 0 || maxLines <= 0 || maxUnits <= 0) {
      throw new IllegalArgumentException("Wave capacities must be positive");
    }
  }

  public boolean accepts(WaveCandidate candidate) {
    return facilityId.equals(candidate.facilityId())
        && !candidate.dispatchBy().isAfter(dispatchByCutoff);
  }

  public boolean canAdd(
      WaveCandidate candidate,
      int selectedShipments,
      int selectedLines,
      int selectedUnits
  ) {
    return selectedShipments < maxShipments
        && (long) selectedLines + candidate.lineCount() <= maxLines
        && (long) selectedUnits + candidate.unitCount() <= maxUnits;
  }
}
