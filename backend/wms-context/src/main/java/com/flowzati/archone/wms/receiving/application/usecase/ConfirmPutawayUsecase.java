package com.flowzati.archone.wms.receiving.application.usecase;

import com.flowzati.archone.wms.receiving.application.invocation.ConfirmPutawayCommand;
import com.flowzati.archone.wms.receiving.application.store.InboundOperationStore;
import com.flowzati.archone.wms.receiving.domain.aggregate.InboundOperation;
import com.flowzati.archone.wms.receiving.domain.valueobject.PutawayLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

public class ConfirmPutawayUsecase {

    private static final Logger log = LoggerFactory.getLogger(ConfirmPutawayUsecase.class);

    private final InboundOperationStore repository;

    public ConfirmPutawayUsecase(InboundOperationStore repository) {
        this.repository = repository;
    }

    @Transactional
    public void handle(ConfirmPutawayCommand command) {
        InboundOperation operation = repository
                .findById(command.inboundOperationId())
                .orElseThrow(() ->
                        new IllegalStateException("Inbound operation not found: " + command.inboundOperationId()));
        operation.completePutaway(
                command.lines().stream()
                        .map(line -> new PutawayLine(
                                line.skuCode(), line.locationId(), line.inDate(), line.expiryDate(), line.quantity()))
                        .toList(),
                command.completedAt());
        repository.save(operation);
        log.info(
                "WMS inbound putaway confirmed: inboundOperationId={}, lineCount={}, status={}",
                operation.id(),
                command.lines().size(),
                operation.status());
    }
}
