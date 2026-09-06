package com.flowzati.archone.orchestration.contract.workflow.order.result;

/** WMS Shipment 回報給 fulfillment Workflow 的互斥最終結果。 */
public enum ShipmentTerminalStatus {
    CANCELLED,
    HANDED_OVER
}
