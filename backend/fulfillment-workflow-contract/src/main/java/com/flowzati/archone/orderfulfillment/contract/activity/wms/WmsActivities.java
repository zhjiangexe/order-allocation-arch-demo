package com.flowzati.archone.orderfulfillment.contract.activity.wms;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/** WMS worker 擁有的 Activity contract；不在 Activity 裡等待 Kafka reply。 */
@ActivityInterface
public interface WmsActivities {

    String TASK_QUEUE = "wms-activities";

    /**
     * 以 committed allocation snapshot 冪等建立 Shipment，並在 transaction 提交後回傳建單
     * receipt。implementation 必須保證 receipt 非 null；技術失敗直接拋出交給 Temporal retry。
     */
    @ActivityMethod(name = "CreateWmsShipment")
    CreateShipmentActivityResult createShipment(CreateShipmentActivityInput input);

    /**
     * 冪等提交 WMS cancellation command；相同 request ID、請求時間與原因可安全重播，不同 immutable
     * request 必須拒絕。Activity 只確認 command 已處理，不等待實體 recovery。
     */
    @ActivityMethod(name = "CancelWmsShipment")
    void requestShipmentCancellation(CancelShipmentActivityInput input);
}
