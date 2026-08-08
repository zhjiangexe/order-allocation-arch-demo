package com.flowzati.archone.wms.inbound.application.usecase;

import com.flowzati.archone.wms.inbound.application.command.RecordInspectionCommand;
import com.flowzati.archone.wms.inbound.domain.model.InboundOperation;
import com.flowzati.archone.wms.inbound.domain.repository.InboundOperationRepository;
import com.flowzati.archone.wms.shared.application.DomainEventPublisher;

public class RecordInspectionUsecase {

  private final InboundOperationRepository repository;
  private final DomainEventPublisher eventPublisher;

  public RecordInspectionUsecase(InboundOperationRepository repository, DomainEventPublisher eventPublisher) {
    this.repository = repository;
    this.eventPublisher = eventPublisher;
  }

  public void handle(RecordInspectionCommand command) {
    InboundOperation operation = required(command.inboundOperationId());
    operation.recordInspection(command.accepted(), command.reason(), command.inspectedAt());
    repository.save(operation);
    operation.releaseEvents().forEach(eventPublisher::publish);
  }

  private InboundOperation required(java.util.UUID operationId) {
    return repository.findById(operationId)
        .orElseThrow(() -> new IllegalStateException("Inbound operation not found: " + operationId));
  }
}
