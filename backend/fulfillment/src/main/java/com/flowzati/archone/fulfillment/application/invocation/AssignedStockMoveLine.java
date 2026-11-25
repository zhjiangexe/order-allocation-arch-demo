package com.flowzati.archone.fulfillment.application.invocation;

import java.util.UUID;

/** {@link RecordStockOperationAssignedCommand} 的一行：一個訂單明細對應的已配貨 move。 */
public record AssignedStockMoveLine(
        UUID orderLineId, UUID moveId, String skuCode, UUID sourceLocationId, int quantity) {}
