package com.flowzati.archone.fulfillment.application.invocation;

import java.time.Instant;
import java.util.UUID;

/** 倉庫已把出貨單交給物流商。 */
public record RecordShipmentHandedOverCommand(UUID orderId, UUID shipmentId, Instant handedOverAt) {}
