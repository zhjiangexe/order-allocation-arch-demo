package com.flowzati.archone.orderfulfillment.contract.activity.wms;

import java.util.Objects;
import java.util.UUID;

/** 只證明 WMS 建單 checkpoint 已提交，不代表 Pick／Pack／Stage 或 carrier handover 完成。 */
public record CreateShipmentActivityResult(UUID shipmentId) {

    public CreateShipmentActivityResult {
        Objects.requireNonNull(shipmentId, "Shipment ID is required");
    }
}
