package com.flowzati.archone.fulfillment.entrypoint.rest.response;

import com.flowzati.archone.fulfillment.application.enums.FulfillmentCancellationStatus;
import com.flowzati.archone.fulfillment.application.result.FulfillmentCancellationResult;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** 取消是長期協調命令；回應代表受理／拒絕，不保證所有非同步 compensation 已完成。 */
public record OrderCancellationResponse(String status, UUID effectiveRequestId) {

    /** 受理 → 202；拒絕或衝突 → 409；其他（例如已取消的重送）→ 200。 */
    public static ResponseEntity<OrderCancellationResponse> toResponseEntity(FulfillmentCancellationResult result) {
        OrderCancellationResponse response = from(result);
        if (result.status() == FulfillmentCancellationStatus.ACCEPTED) {
            return ResponseEntity.accepted().body(response);
        }
        if (result.status() == FulfillmentCancellationStatus.REJECTED
                || result.status() == FulfillmentCancellationStatus.CONFLICT) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }
        return ResponseEntity.ok(response);
    }

    static OrderCancellationResponse from(FulfillmentCancellationResult result) {
        return new OrderCancellationResponse(result.status().name(), result.effectiveRequestId());
    }
}
