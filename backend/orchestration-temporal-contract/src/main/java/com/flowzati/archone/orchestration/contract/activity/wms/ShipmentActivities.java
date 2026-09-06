package com.flowzati.archone.orchestration.contract.activity.wms;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/** WMS Shipment capability 執行的 fulfillment Activity contract；不在 Activity 裡等待 Kafka reply。 */
@ActivityInterface
public interface ShipmentActivities {

    String TASK_QUEUE = "wms-activities";

    /**
     * 向 WMS 下達已配貨的出庫需求，以 committed stock-operation assignment snapshot 冪等建立 Shipment。
     * transaction 提交後回傳 receipt，不代表 Wave Release、備貨或 carrier handover 已完成。
     * implementation 必須保證 receipt 非 null；技術失敗直接拋出交給 Temporal retry。
     */
    // 保留既有 Activity type，Java 方法改名不改變 Temporal history 中的識別。
    @ActivityMethod(name = "CreateWmsShipment")
    ReleaseToWarehouseActivityResult releaseToWarehouse(ReleaseToWarehouseActivityInput input);

    /**
     * 冪等提交 WMS cancellation command；相同 request ID、請求時間與原因可安全重播，不同 immutable
     * request 必須拒絕。Activity 回傳受理或拒絕結果，不等待實體 recovery。
     * 舊版 void Activity history 沒有回傳值，重播時仍依既有行為等待 Shipment terminal Signal。
     */
    @ActivityMethod(name = "CancelWmsShipment")
    CancelShipmentActivityStatus requestShipmentCancellation(CancelShipmentActivityInput input);
}
