package com.flowzati.archone.wms.inbound.application.usecase;

import com.flowzati.archone.wms.inbound.application.command.RegisterInboundOperationCommand;
import com.flowzati.archone.wms.inbound.domain.valueobject.InboundLine;
import com.flowzati.archone.wms.inbound.domain.aggregate.InboundOperation;
import com.flowzati.archone.wms.inbound.domain.repository.InboundOperationRepository;
import com.flowzati.archone.wms.shared.application.DomainEventPublisher;

public class RegisterInboundOperationUsecase {

  private final InboundOperationRepository repository;
  private final DomainEventPublisher eventPublisher;

  public RegisterInboundOperationUsecase(
      InboundOperationRepository repository,
      DomainEventPublisher eventPublisher
  ) {
    this.repository = repository;
    this.eventPublisher = eventPublisher;
  }

  public InboundOperation handle(RegisterInboundOperationCommand command) {
    return repository.findByExternalReference(command.externalReference())
        .orElseGet(() -> register(command));
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
    operation.releaseEvents().forEach(eventPublisher::publish);
    return operation;
  }
}
