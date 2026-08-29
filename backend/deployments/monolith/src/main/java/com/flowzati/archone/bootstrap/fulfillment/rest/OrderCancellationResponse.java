package com.flowzati.archone.bootstrap.fulfillment.rest;

import com.flowzati.archone.process.fulfillment.cancellation.FulfillmentCancellationResult;
import java.util.UUID;

/** 取消是長期協調命令；回應代表受理／拒絕，不保證所有非同步 compensation 已完成。 */
public record OrderCancellationResponse(String status, UUID effectiveRequestId, String detail) {

    static OrderCancellationResponse from(FulfillmentCancellationResult result) {
        return new OrderCancellationResponse(result.status().name(), result.effectiveRequestId(), result.detail());
    }
}
