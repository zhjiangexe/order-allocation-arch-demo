package com.flowzati.archone.orderfulfillment.entrypoint.rest;

import com.flowzati.archone.orderfulfillment.application.FulfillmentCancellationResult;
import java.util.UUID;

/** 取消是長期協調命令；回應代表受理／拒絕，不保證所有非同步 compensation 已完成。 */
public record OrderCancellationResponse(String status, UUID effectiveRequestId) {

    static OrderCancellationResponse from(FulfillmentCancellationResult result) {
        return new OrderCancellationResponse(result.status().name(), result.effectiveRequestId());
    }
}
