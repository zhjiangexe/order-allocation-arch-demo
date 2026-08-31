package com.flowzati.archone.wms.picking.application.result;

import java.time.Instant;
import java.util.UUID;

/** WMS 實揀任務的唯讀表示。 */
public record PickTaskView(
        UUID pickTaskId,
        UUID orderLineId,
        UUID moveId,
        String skuCode,
        UUID sourceLocationId,
        int requestedQuantity,
        int pickedQuantity,
        String status,
        Instant confirmedAt) {}
