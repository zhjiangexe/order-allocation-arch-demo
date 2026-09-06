package com.flowzati.archone.orchestration.contract.activity.wms;

import java.util.Objects;
import java.util.UUID;

/** 倉庫出庫需求已建立的 receipt；不代表 Wave Release、Pick／Pack／Stage 或 carrier handover 完成。 */
public record ReleaseToWarehouseActivityResult(UUID shipmentId) {

    public ReleaseToWarehouseActivityResult {
        Objects.requireNonNull(shipmentId, "Shipment ID is required");
    }
}
