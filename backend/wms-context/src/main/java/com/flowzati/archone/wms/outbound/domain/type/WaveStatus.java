package com.flowzati.archone.wms.outbound.domain.type;

/** Wave 只管理 picking work 的規劃、釋放與完成，不鏡像 Shipment 後續 Pack／Stage 狀態。 */
public enum WaveStatus {
    PLANNED,
    RELEASED,
    COMPLETED
}
