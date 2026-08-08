package com.flowzati.archone.ordering.entrypoint.kafka;

import com.flowzati.archone.contracts.promising.v1.BackorderCreatedIntegrationEvent;
import com.flowzati.archone.stock.application.event.PromisingEventTopics;
import com.flowzati.archone.messaging.api.InboundCommand;
import com.flowzati.archone.messaging.api.MessageMetadata;
import com.flowzati.archone.messaging.events.IntegrationEventHandler;
import com.flowzati.archone.ordering.application.command.RecordOrderBackorderCommand;
import com.flowzati.archone.ordering.application.usecase.RecordOrderBackorderUsecase;
import org.springframework.stereotype.Component;

@Component
class BackorderCreatedIntegrationEventHandler
    implements IntegrationEventHandler<BackorderCreatedIntegrationEvent> {

  private final RecordOrderBackorderUsecase recordOrderBackorderUsecase;

  BackorderCreatedIntegrationEventHandler(RecordOrderBackorderUsecase recordOrderBackorderUsecase) {
    this.recordOrderBackorderUsecase = recordOrderBackorderUsecase;
  }

  @Override
  public String destination() {
    return PromisingEventTopics.ALLOCATION_EVENTS;
  }

  @Override
  public String eventType() {
    return BackorderCreatedIntegrationEvent.EVENT_TYPE;
  }

  @Override
  public Class<BackorderCreatedIntegrationEvent> eventClass() {
    return BackorderCreatedIntegrationEvent.class;
  }

  @Override
  public void handleTyped(BackorderCreatedIntegrationEvent event, MessageMetadata metadata) {
    recordOrderBackorderUsecase.handle(new InboundCommand<>(
        new RecordOrderBackorderCommand(event.getOrderId(), event.getBackorderedSince()), metadata));
  }
}
