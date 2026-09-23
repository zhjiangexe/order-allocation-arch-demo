package com.flowzati.archone.fulfillment.application.usecase;

import com.flowzati.archone.foundation.configuration.FulfillmentOrchestrationMode;
import com.flowzati.archone.fulfillment.application.state.CancellationProcess;
import com.flowzati.archone.fulfillment.application.store.CancellationProcessStore;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(
        name = FulfillmentOrchestrationMode.ORCHESTRATION_MODE,
        havingValue = FulfillmentOrchestrationMode.EVENTS,
        matchIfMissing = true)
public class GetCancellationProcessUsecase {
    private final CancellationProcessStore store;

    public GetCancellationProcessUsecase(CancellationProcessStore store) {
        this.store = store;
    }

    @Transactional(readOnly = true)
    public Optional<CancellationProcess> get(UUID orderId, UUID requestId) {
        return store.find(requestId).filter(process -> process.orderId().equals(orderId));
    }
}
