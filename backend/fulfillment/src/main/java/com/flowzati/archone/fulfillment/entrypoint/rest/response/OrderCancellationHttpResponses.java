package com.flowzati.archone.fulfillment.entrypoint.rest.response;

import com.flowzati.archone.fulfillment.application.result.FulfillmentCancellationResult;
import com.flowzati.archone.fulfillment.application.state.FulfillmentCancellationStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

public final class OrderCancellationHttpResponses {

    private OrderCancellationHttpResponses() {}

    public static ResponseEntity<OrderCancellationResponse> from(FulfillmentCancellationResult result) {
        OrderCancellationResponse response = OrderCancellationResponse.from(result);
        if (result.status() == FulfillmentCancellationStatus.ACCEPTED) {
            return ResponseEntity.accepted().body(response);
        }
        if (result.status() == FulfillmentCancellationStatus.REJECTED
                || result.status() == FulfillmentCancellationStatus.CONFLICT) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }
        return ResponseEntity.ok(response);
    }
}
