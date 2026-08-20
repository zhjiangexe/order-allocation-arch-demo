package com.flowzati.archone.wms.outbound.application.query;

import java.util.UUID;

/** Shipment 保存的 committed allocation line 快照。 */
public record ShipmentLineView(UUID orderLineId, UUID moveId, String skuCode, UUID sourceLocationId, int quantity) {}
