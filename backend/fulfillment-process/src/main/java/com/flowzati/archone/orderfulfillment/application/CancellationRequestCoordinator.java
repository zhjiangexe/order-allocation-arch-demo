package com.flowzati.archone.orderfulfillment.application;

import com.flowzati.archone.orderfulfillment.application.invocation.FulfillmentCancellationCommand;

/** 依執行模式選擇取消請求的受理入口；後續流程由 process manager 或 Temporal workflow 推進。 */
@FunctionalInterface
public interface CancellationRequestCoordinator {

    FulfillmentCancellationResult request(FulfillmentCancellationCommand request);
}
