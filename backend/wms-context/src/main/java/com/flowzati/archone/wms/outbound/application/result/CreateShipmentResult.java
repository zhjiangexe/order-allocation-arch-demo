package com.flowzati.archone.wms.outbound.application.result;

import java.util.Objects;
import java.util.UUID;

/**
 * WMS application layer 的穩定建單結果。
 *
 * <p>它只表示 Shipment 已建立或依 picking ID 冪等讀回；不暴露 domain aggregate，也不表示
 * Pick／Pack／Stage 或 carrier handover 已完成。Event-driven consumer 可以忽略結果，Temporal
 * Activity adapter 則將它映射成自己的 {@code CreateShipmentActivityResult} contract。
 */
public record CreateShipmentResult(UUID shipmentId) {

    public CreateShipmentResult {
        Objects.requireNonNull(shipmentId, "Shipment ID is required");
    }
}
