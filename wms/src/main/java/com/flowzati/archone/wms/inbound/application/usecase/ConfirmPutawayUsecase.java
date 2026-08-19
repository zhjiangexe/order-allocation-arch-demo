package com.flowzati.archone.wms.inbound.application.usecase;

import com.flowzati.archone.wms.inbound.application.command.ConfirmPutawayCommand;
import com.flowzati.archone.wms.inbound.domain.aggregate.InboundOperation;
import com.flowzati.archone.wms.inbound.domain.valueobject.PutawayLine;
import com.flowzati.archone.wms.inbound.domain.repository.InboundOperationRepository;
import com.flowzati.archone.wms.shared.application.DomainEventPublisher;

public class ConfirmPutawayUsecase {

  private final InboundOperationRepository repository;
  private final DomainEventPublisher eventPublisher;

  public ConfirmPutawayUsecase(InboundOperationRepository repository, DomainEventPublisher eventPublisher) {
    this.repository = repository;
    this.eventPublisher = eventPublisher;
  }

  public void handle(ConfirmPutawayCommand command) {
    InboundOperation operation = repository.findById(command.inboundOperationId())
        .orElseThrow(() -> new IllegalStateException(
            "Inbound operation not found: " + command.inboundOperationId()));
    operation.completePutaway(command.lines().stream()
        .map(line -> new PutawayLine(
            line.skuCode(), line.locationId(), line.inDate(), line.expiryDate(), line.quantity()))
        .toList(), command.completedAt());
    repository.save(operation);
    operation.releaseEvents().forEach(eventPublisher::publish);
  }
}
