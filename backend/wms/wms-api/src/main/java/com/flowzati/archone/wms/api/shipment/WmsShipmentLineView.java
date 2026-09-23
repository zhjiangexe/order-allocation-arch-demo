package com.flowzati.archone.wms.api.shipment;

import java.util.UUID;

public record WmsShipmentLineView(UUID orderLineId, UUID moveId, String skuCode, UUID sourceLocationId, int quantity) {}
