package com.flowzati.archone.wms.shipment.application.result;

import java.util.UUID;

/** Shipment 保存的 assigned movement 快照。 */
public record ShipmentLineView(UUID orderLineId, UUID moveId, String skuCode, UUID sourceLocationId, int quantity) {}
