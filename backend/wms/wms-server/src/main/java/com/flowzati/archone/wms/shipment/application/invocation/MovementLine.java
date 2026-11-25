package com.flowzati.archone.wms.shipment.application.invocation;

import java.util.UUID;

/** {@link CreateShipmentCommand} 的一行：已指派的一筆 move。 */
public record MovementLine(
        UUID orderLineId,
        UUID moveId,
        String skuCode,
        /** 目前由 assigned movement snapshot 提供；若改由 WMS location directive 決定需重畫邊界。 */
        UUID sourceLocationId,
        int quantity) {

    public MovementLine {
        if (orderLineId == null || moveId == null || sourceLocationId == null) {
            throw new IllegalArgumentException("Movement line requires order line, move and location IDs");
        }
        if (skuCode == null || skuCode.isBlank() || quantity <= 0) {
            throw new IllegalArgumentException("Movement line requires SKU and positive quantity");
        }
    }
}
