package com.flowzati.archone.process.fulfillment.cancellation;

/** 依 deployment orchestration mode 選擇 events 或 Temporal 的取消協調方式。 */
@FunctionalInterface
public interface FulfillmentCancellationCoordinator {

    FulfillmentCancellationResult request(FulfillmentCancellationRequest request);
}
