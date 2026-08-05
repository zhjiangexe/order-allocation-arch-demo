package com.flowzati.archone.ordering.entrypoint.kafka;

import com.flowzati.archone.stock.application.event.BackorderCreatedIntegrationEvent;
import com.flowzati.archone.stock.application.event.PromisingEventTopics;
import com.flowzati.archone.common.inbox.InboundCommand;
import com.flowzati.archone.common.inbox.MessageMetadata;
import com.flowzati.archone.common.messaging.kafka.KafkaIntegrationEventHandler;
import com.flowzati.archone.ordering.application.command.RecordOrderBackorderCommand;
import com.flowzati.archone.ordering.application.usecase.RecordOrderBackorderUsecase;
import org.springframework.stereotype.Component;

@Component
class BackorderCreatedIntegrationEventHandler
    implements KafkaIntegrationEventHandler<BackorderCreatedIntegrationEvent> {

  private final RecordOrderBackorderUsecase recordOrderBackorderUsecase;

  BackorderCreatedIntegrationEventHandler(RecordOrderBackorderUsecase recordOrderBackorderUsecase) {
    this.recordOrderBackorderUsecase = recordOrderBackorderUsecase;
  }

  @Override
  public String topic() {
    return PromisingEventTopics.ALLOCATION_EVENTS;
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
