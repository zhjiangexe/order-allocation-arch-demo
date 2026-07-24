package com.flowzati.archone.common.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowzati.archone.common.integration.IntegrationEvent;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class OutboxAppender {

  private final OutboxRepo outboxRepo;
  private final ObjectMapper objectMapper;

  public OutboxAppender(OutboxRepo outboxRepo, ObjectMapper objectMapper) {
    this.outboxRepo = outboxRepo;
    this.objectMapper = objectMapper;
  }

  public void append(
      IntegrationEvent event,
      String aggregateType,
      UUID aggregateId,
      String route,
      Instant occurredAt
  ) {
    try {
      outboxRepo.append(new Outbox(
          event.getEventId(),
          aggregateType,
          aggregateId.toString(),
          event.getClass().getSimpleName(),
          route,
          objectMapper.writeValueAsString(event),
          occurredAt
      ));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Cannot serialize integration event", exception);
    }
  }
}
