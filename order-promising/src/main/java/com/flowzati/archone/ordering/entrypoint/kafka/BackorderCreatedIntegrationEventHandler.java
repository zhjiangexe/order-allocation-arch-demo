package com.flowzati.archone.ordering.entrypoint.kafka;

import com.flowzati.archone.allocation.application.event.BackorderCreatedIntegrationEvent;
import com.flowzati.archone.allocation.application.event.PromisingEventTopics;
import com.flowzati.archone.common.inbox.InboundCommand;
import com.flowzati.archone.common.inbox.MessageMetadata;
import com.flowzati.archone.common.messaging.kafka.KafkaIntegrationEventHandler;
import com.flowzati.archone.ordering.application.command.RecordBackorderCommand;
import com.flowzati.archone.ordering.application.usecase.ConfirmOrderUsecase;
import org.springframework.stereotype.Component;

@Component
class BackorderCreatedIntegrationEventHandler
    implements KafkaIntegrationEventHandler<BackorderCreatedIntegrationEvent> {

  private final ConfirmOrderUsecase confirmOrderUsecase;

  BackorderCreatedIntegrationEventHandler(ConfirmOrderUsecase confirmOrderUsecase) {
    this.confirmOrderUsecase = confirmOrderUsecase;
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
    confirmOrderUsecase.recordBackorder(new InboundCommand<>(
        new RecordBackorderCommand(event.getOrderId(), event.getBackorderedSince()), metadata));
  }
}
