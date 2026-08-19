package com.flowzati.archone.orderfulfillment.contract.workflow;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Workflow 專用訊息，由 adapter 從 WMS 的同名 domain/integration event 映射而來。 */
public record ShipmentHandedOverToCarrierSignal(UUID orderId, UUID shipmentId, Instant handedOverAt) {

    public ShipmentHandedOverToCarrierSignal {
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(shipmentId, "Shipment ID is required");
        Objects.requireNonNull(handedOverAt, "Handover time is required");
    }
}
