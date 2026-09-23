package com.flowzati.archone.fulfillment.entrypoint.rest;

import com.flowzati.archone.foundation.configuration.FulfillmentOrchestrationMode;
import com.flowzati.archone.fulfillment.application.invocation.FulfillmentCancellationCommand;
import com.flowzati.archone.fulfillment.application.result.FulfillmentCancellationResult;
import com.flowzati.archone.fulfillment.application.usecase.AcceptCancellationRequestUsecase;
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

/** 外部只提交 cancellation request；不得直接取消 Order aggregate。 */
@RestController
@RequestMapping("/orders")
@ConditionalOnProperty(
        name = FulfillmentOrchestrationMode.ORCHESTRATION_MODE,
        havingValue = FulfillmentOrchestrationMode.EVENTS,
        matchIfMissing = true)
public class EventDrivenOrderCancellationRest {

    private final AcceptCancellationRequestUsecase acceptCancellationRequestUsecase;

    public EventDrivenOrderCancellationRest(AcceptCancellationRequestUsecase acceptCancellationRequestUsecase) {
        this.acceptCancellationRequestUsecase = acceptCancellationRequestUsecase;
    }

    @PostMapping("/{orderId}/cancellation-requests")
    public ResponseEntity<OrderCancellationResponse> requestCancellation(
            @PathVariable(name = "orderId") UUID orderId, @Valid @RequestBody OrderCancellationRequest body) {
        FulfillmentCancellationCommand command =
                new FulfillmentCancellationCommand(body.requestId(), orderId, body.requestedAt(), body.reason());
        FulfillmentCancellationResult result = acceptCancellationRequestUsecase.accept(command);
        return OrderCancellationHttpResponses.from(result);
    }
}
