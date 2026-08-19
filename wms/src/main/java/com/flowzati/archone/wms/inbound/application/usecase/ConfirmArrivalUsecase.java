package com.flowzati.archone.wms.inbound.application.usecase;

import com.flowzati.archone.wms.inbound.application.command.ConfirmArrivalCommand;
import com.flowzati.archone.wms.inbound.domain.aggregate.InboundOperation;
import com.flowzati.archone.wms.inbound.domain.repository.InboundOperationRepository;
import com.flowzati.archone.wms.shared.application.DomainEventPublisher;

public class ConfirmArrivalUsecase {

  private final InboundOperationRepository repository;
  private final DomainEventPublisher eventPublisher;

  public ConfirmArrivalUsecase(InboundOperationRepository repository, DomainEventPublisher eventPublisher) {
    this.repository = repository;
    this.eventPublisher = eventPublisher;
  }

  public void handle(ConfirmArrivalCommand command) {
    InboundOperation operation = required(command.inboundOperationId());
    operation.confirmArrival(command.arrivedAt());
    repository.save(operation);
    operation.releaseEvents().forEach(eventPublisher::publish);
  }

  private InboundOperation required(java.util.UUID operationId) {
    return repository.findById(operationId)
        .orElseThrow(() -> new IllegalStateException("Inbound operation not found: " + operationId));
  }
}
