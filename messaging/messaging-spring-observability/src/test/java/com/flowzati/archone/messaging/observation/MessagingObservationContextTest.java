package com.flowzati.archone.messaging.observation;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageBuilder;
import com.flowzati.archone.messaging.api.MessageContext;
import com.flowzati.archone.messaging.api.MessageHeaders;
import com.flowzati.archone.messaging.api.MessagePublicationContext;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MessagingObservationContextTest {

  private static final String TRACEPARENT =
      "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

  @Test
  void producerContextInjectsPropagationFieldsIntoANewImmutableEnvelope() {
    Message original = message();
    ProducerMessageObservationContext context = new ProducerMessageObservationContext(
        original, new MessagePublicationContext("order-events", "prod.order-events"));

    context.getSetter().set(context.getCarrier(), "TraceParent", TRACEPARENT);

    assertThat(original.header(MessageHeaders.TRACEPARENT)).isEmpty();
    assertThat(context.message().header(MessageHeaders.TRACEPARENT)).contains(TRACEPARENT);
  }

  @Test
  void consumerContextExtractsPropagationFieldsCaseInsensitively() {
    Message traced = message().withHeader(MessageHeaders.TRACEPARENT, TRACEPARENT);
    ConsumerMessageObservationContext context = new ConsumerMessageObservationContext(
        traced, new MessageContext("ordering", "order-events", 2));

    assertThat(context.getGetter().get(context.getCarrier(), "TraceParent"))
        .isEqualTo(TRACEPARENT);
  }

  private Message message() {
    return MessageBuilder.withPayload("{}")
        .withId(UUID.fromString("00000000-0000-0000-0000-000000000101"))
        .withType("ordering.order-placed.v1")
        .withPartitionId("order-101")
        .withMessageDate(Instant.parse("2026-08-10T00:00:00Z"))
        .build();
  }
}
