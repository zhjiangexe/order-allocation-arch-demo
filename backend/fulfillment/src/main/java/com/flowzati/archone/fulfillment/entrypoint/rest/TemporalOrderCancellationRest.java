package com.flowzati.archone.fulfillment.entrypoint.rest;

import com.flowzati.archone.foundation.configuration.FulfillmentOrchestrationMode;
import com.flowzati.archone.fulfillment.application.invocation.FulfillmentCancellationCommand;
import com.flowzati.archone.fulfillment.application.usecase.RequestWorkflowCancellationUsecase;
import com.flowzati.archone.fulfillment.entrypoint.rest.request.OrderCancellationRequest;
import com.flowzati.archone.fulfillment.entrypoint.rest.response.OrderCancellationHttpResponses;
import com.flowzati.archone.fulfillment.entrypoint.rest.response.OrderCancellationResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Temporal deployment submits cancellation requests to the order fulfillment Workflow. */
@RestController
@RequestMapping("/orders")
@ConditionalOnProperty(
        name = FulfillmentOrchestrationMode.ORCHESTRATION_MODE,
        havingValue = FulfillmentOrchestrationMode.TEMPORAL)
public class TemporalOrderCancellationRest {

    private final RequestWorkflowCancellationUsecase requestWorkflowCancellationUsecase;

    public TemporalOrderCancellationRest(RequestWorkflowCancellationUsecase requestWorkflowCancellationUsecase) {
        this.requestWorkflowCancellationUsecase = requestWorkflowCancellationUsecase;
    }

    @PostMapping("/{orderId}/cancellation-requests")
    public ResponseEntity<OrderCancellationResponse> requestCancellation(
            @PathVariable(name = "orderId") UUID orderId, @Valid @RequestBody OrderCancellationRequest body) {
        return OrderCancellationHttpResponses.from(requestWorkflowCancellationUsecase.request(command(orderId, body)));
    }

    private static FulfillmentCancellationCommand command(UUID orderId, OrderCancellationRequest body) {
        return new FulfillmentCancellationCommand(body.requestId(), orderId, body.requestedAt(), body.reason());
    }
}
