package com.flowzati.archone.fulfillment.application.enums;

/** WMS 對一筆取消請求的處理結果。 */
public enum WmsCancellationOutcome {
    SHIPMENT_CANCELLED,
    NO_SHIPMENT,
    REJECTED,
    MULTIPLE_SHIPMENTS
}
