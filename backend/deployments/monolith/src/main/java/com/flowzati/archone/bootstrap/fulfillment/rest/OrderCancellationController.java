package com.flowzati.archone.bootstrap.fulfillment.rest;

import com.flowzati.archone.ordering.domain.exception.OrderCancellationRequestConflictException;
import com.flowzati.archone.process.fulfillment.cancellation.FulfillmentCancellationConflictException;
import com.flowzati.archone.process.fulfillment.cancellation.FulfillmentCancellationCoordinator;
import com.flowzati.archone.process.fulfillment.cancellation.FulfillmentCancellationRequest;
import com.flowzati.archone.process.fulfillment.cancellation.FulfillmentCancellationResult;
import com.flowzati.archone.process.fulfillment.cancellation.FulfillmentCancellationStatus;
import com.flowzati.archone.process.fulfillment.cancellation.FulfillmentCancellationUnavailableException;
import com.flowzati.archone.wms.outbound.domain.exception.ShipmentCancellationRequestConflictException;
import jakarta.validation.Valid;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 外部只提交 cancellation request；不得直接取消 Order aggregate。 */
@RestController
@RequestMapping("/orders")
public class OrderCancellationController {

    private final FulfillmentCancellationCoordinator cancellationCoordinator;

    public OrderCancellationController(FulfillmentCancellationCoordinator cancellationCoordinator) {
        this.cancellationCoordinator = cancellationCoordinator;
    }

    @PostMapping("/{orderId}/cancellation-requests")
    public ResponseEntity<OrderCancellationResponse> requestCancellation(
            @PathVariable(name = "orderId") UUID orderId, @Valid @RequestBody OrderCancellationRequest body) {
        FulfillmentCancellationResult result = cancellationCoordinator.request(
                new FulfillmentCancellationRequest(body.requestId(), orderId, body.requestedAt(), body.reason()));
        OrderCancellationResponse response = OrderCancellationResponse.from(result);
        if (result.status() == FulfillmentCancellationStatus.ACCEPTED) {
            return ResponseEntity.accepted().body(response);
        }
        if (result.status() == FulfillmentCancellationStatus.REJECTED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }
        return ResponseEntity.ok(response);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<String> handleNotFound(NoSuchElementException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleInvalidRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(exception.getMessage());
    }

    @ExceptionHandler({
        FulfillmentCancellationConflictException.class,
        FulfillmentCancellationUnavailableException.class,
        OrderCancellationRequestConflictException.class,
        ShipmentCancellationRequestConflictException.class
    })
    public ResponseEntity<String> handleConflict(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(exception.getMessage());
    }
}
