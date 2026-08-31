package com.flowzati.archone.wms.receiving.application.usecase;

import com.flowzati.archone.wms.receiving.application.invocation.ConfirmArrivalCommand;
import com.flowzati.archone.wms.receiving.application.store.InboundOperationStore;
import com.flowzati.archone.wms.receiving.domain.aggregate.InboundOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

public class ConfirmArrivalUsecase {

    private static final Logger log = LoggerFactory.getLogger(ConfirmArrivalUsecase.class);

    private final InboundOperationStore repository;

    public ConfirmArrivalUsecase(InboundOperationStore repository) {
        this.repository = repository;
    }

    @Transactional
    public void handle(ConfirmArrivalCommand command) {
        InboundOperation operation = required(command.inboundOperationId());
        operation.confirmArrival(command.arrivedAt());
        repository.save(operation);
        log.info("WMS inbound arrival confirmed: inboundOperationId={}, status={}", operation.id(), operation.status());
    }

    private InboundOperation required(java.util.UUID operationId) {
        return repository
                .findById(operationId)
                .orElseThrow(() -> new IllegalStateException("Inbound operation not found: " + operationId));
    }
}
