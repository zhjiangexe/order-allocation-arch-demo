package com.flowzati.archone.wms.inbound.application.usecase;

import com.flowzati.archone.wms.inbound.application.command.RegisterInboundOperationCommand;
import com.flowzati.archone.wms.inbound.application.store.InboundOperationStore;
import com.flowzati.archone.wms.inbound.domain.aggregate.InboundOperation;
import com.flowzati.archone.wms.inbound.domain.valueobject.InboundLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

public class RegisterInboundOperationUsecase {

    private static final Logger log = LoggerFactory.getLogger(RegisterInboundOperationUsecase.class);

    private final InboundOperationStore repository;

    public RegisterInboundOperationUsecase(InboundOperationStore repository) {
        this.repository = repository;
    }

    @Transactional
    public InboundOperation handle(RegisterInboundOperationCommand command) {
        return repository.findByExternalReference(command.externalReference()).orElseGet(() -> register(command));
    }

    private InboundOperation register(RegisterInboundOperationCommand command) {
        InboundOperation operation = InboundOperation.register(
                command.inboundOperationId(),
                command.ownerId(),
                command.facilityId(),
                command.externalReference(),
                command.lines().stream()
                        .map(line -> new InboundLine(line.skuCode(), line.quantity()))
                        .toList(),
                command.registeredAt());
        repository.save(operation);
        log.info(
                "WMS inbound operation registered: inboundOperationId={}, externalReference={}, lineCount={}, status={}",
                operation.id(),
                operation.externalReference(),
                operation.expectedLines().size(),
                operation.status());
        return operation;
    }
}
