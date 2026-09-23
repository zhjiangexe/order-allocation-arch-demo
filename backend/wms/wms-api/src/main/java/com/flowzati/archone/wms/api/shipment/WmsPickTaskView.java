package com.flowzati.archone.wms.api.shipment;

import java.time.Instant;
import java.util.UUID;

public record WmsPickTaskView(
        UUID pickTaskId,
        UUID orderLineId,
        UUID moveId,
        String skuCode,
        UUID sourceLocationId,
        int requestedQuantity,
        int pickedQuantity,
        String status,
        Instant confirmedAt) {}
