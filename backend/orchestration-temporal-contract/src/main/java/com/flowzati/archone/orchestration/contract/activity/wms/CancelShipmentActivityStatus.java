package com.flowzati.archone.orchestration.contract.activity.wms;

/** WMS 對取消命令的受理結果；ACCEPTED 不代表實體 recovery 或 Shipment cancellation 已完成。 */
public enum CancelShipmentActivityStatus {
    ACCEPTED,
    REJECTED
}
