package com.flowzati.archone.wms.outbound.domain.valueobject;

import java.time.Instant;
import java.util.UUID;

/** Wave 對 Shipment 的規劃結果；只保存 release 所需的 identity 與可稽核排序資訊。 */
public record WaveAssignment(UUID shipmentId, int lineCount, int unitCount, int releasePriority, Instant dispatchBy) {

    public WaveAssignment {
        if (shipmentId == null || dispatchBy == null || lineCount <= 0 || unitCount <= 0) {
            throw new IllegalArgumentException("Wave assignment requires shipment and positive quantities");
        }
        if (releasePriority < 0 || releasePriority > 100) {
            throw new IllegalArgumentException("Release priority must be between 0 and 100");
        }
    }

    public static WaveAssignment from(WaveCandidate candidate) {
        return new WaveAssignment(
                candidate.shipmentId(),
                candidate.lineCount(),
                candidate.unitCount(),
                candidate.releasePriority(),
                candidate.dispatchBy());
    }
}
