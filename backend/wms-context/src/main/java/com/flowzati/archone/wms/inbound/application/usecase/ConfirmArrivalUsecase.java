package com.flowzati.archone.wms.inbound.application.usecase;

import com.flowzati.archone.wms.inbound.application.command.ConfirmArrivalCommand;
import com.flowzati.archone.wms.inbound.domain.aggregate.InboundOperation;
import com.flowzati.archone.wms.inbound.domain.repository.InboundOperationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

public class ConfirmArrivalUsecase {

    private static final Logger log = LoggerFactory.getLogger(ConfirmArrivalUsecase.class);

    private final InboundOperationRepository repository;

    public ConfirmArrivalUsecase(InboundOperationRepository repository) {
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
