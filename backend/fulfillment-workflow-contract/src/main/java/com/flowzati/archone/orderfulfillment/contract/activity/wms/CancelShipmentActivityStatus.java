package com.flowzati.archone.orderfulfillment.contract.activity.wms;

/** WMS 對 Shipment 取消請求的最終安全決策。 */
public enum CancelShipmentActivityStatus {
    /** Shipment 已安全取消，不需要再等待 WMS fact。 */
    CANCELLED,

    /** WMS 已開始不可安全中止的履約作業，無法取消 Shipment。 */
    REJECTED
}
