package com.flowzati.archone.wms.inbound.application.usecase;

import com.flowzati.archone.wms.inbound.application.command.RecordInspectionCommand;
import com.flowzati.archone.wms.inbound.domain.aggregate.InboundOperation;
import com.flowzati.archone.wms.inbound.domain.repository.InboundOperationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

public class RecordInspectionUsecase {

    private static final Logger log = LoggerFactory.getLogger(RecordInspectionUsecase.class);

    private final InboundOperationRepository repository;

    public RecordInspectionUsecase(InboundOperationRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void handle(RecordInspectionCommand command) {
        InboundOperation operation = required(command.inboundOperationId());
        operation.recordInspection(command.accepted(), command.reason(), command.inspectedAt());
        repository.save(operation);
        log.info(
                "WMS inbound inspection recorded: inboundOperationId={}, accepted={}, status={}",
                operation.id(),
                command.accepted(),
                operation.status());
    }

    private InboundOperation required(java.util.UUID operationId) {
        return repository
                .findById(operationId)
                .orElseThrow(() -> new IllegalStateException("Inbound operation not found: " + operationId));
    }
}
