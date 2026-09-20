package com.flowzati.archone.orderfulfillment.application.usecase;

import com.flowzati.archone.orderfulfillment.application.CancellationProcess;
import com.flowzati.archone.orderfulfillment.application.port.CancellationProcessStore;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "archone.fulfillment.orchestration-mode", havingValue = "events", matchIfMissing = true)
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
