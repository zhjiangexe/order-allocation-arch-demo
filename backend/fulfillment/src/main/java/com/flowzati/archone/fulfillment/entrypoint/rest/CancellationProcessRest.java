package com.flowzati.archone.fulfillment.entrypoint.rest;

import com.flowzati.archone.foundation.configuration.FulfillmentOrchestrationMode;
import com.flowzati.archone.fulfillment.application.state.CancellationProcess;
import com.flowzati.archone.fulfillment.application.usecase.GetCancellationProcessUsecase;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders/{orderId}/cancellation-requests")
@ConditionalOnProperty(
        name = FulfillmentOrchestrationMode.ORCHESTRATION_MODE,
        havingValue = FulfillmentOrchestrationMode.EVENTS,
        matchIfMissing = true)
public class CancellationProcessRest {
    private final GetCancellationProcessUsecase getCancellationProcess;

    public CancellationProcessRest(GetCancellationProcessUsecase getCancellationProcess) {
        this.getCancellationProcess = getCancellationProcess;
    }

    @GetMapping("/{requestId}")
    public ResponseEntity<CancellationProcess> get(
            @PathVariable(name = "orderId") UUID orderId, @PathVariable(name = "requestId") UUID requestId) {
        return getCancellationProcess
                .get(orderId, requestId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
