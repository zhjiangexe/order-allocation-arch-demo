package com.flowzati.archone.wms.outbound.domain.type;

/** 取消結果讓 command caller 能明確分支，而不是靠例外猜流程狀態。 */
public enum ShipmentCancellationStatus {
    CANCELLED,
    ALREADY_CANCELLED,
    PUTBACK_REQUIRED,
    REJECTED_AFTER_HANDOVER
}
