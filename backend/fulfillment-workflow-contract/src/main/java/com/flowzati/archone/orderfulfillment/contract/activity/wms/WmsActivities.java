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
     * 冪等要求 WMS 取消 Shipment，並等待 WMS 回傳最終決策。WMS 必須在此 Activity 完成前判斷
     * Shipment 是否仍可安全取消；Workflow 不等待後續的停止／putback Signal。
     */
    @ActivityMethod(name = "CancelWmsShipment")
    CancelShipmentActivityStatus cancelShipment(CancelShipmentActivityInput input);
}
