package com.flowzati.archone.messaging.producer.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.messaging.api.MapBasedChannelMapping;
import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageBuilder;
import com.flowzati.archone.messaging.api.MessageHeaders;
import com.flowzati.archone.messaging.api.MessageInterceptor;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MessageProducerImplTest {

  private static final UUID GENERATED_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final Instant NOW = Instant.parse("2026-08-09T09:00:00Z");

  @Test
  void mapsTheChannelNormalizesHeadersAndDelegatesOnce() {
    CapturingImplementation implementation = new CapturingImplementation();
    MessageProducerImpl producer = producer(implementation, List.of());

    producer.send("order-events", baseMessage());

    assertThat(implementation.destination).isEqualTo("prod.order-events");
    assertThat(implementation.messages).singleElement().satisfies(message -> {
      assertThat(message.id()).isEqualTo(GENERATED_ID);
      assertThat(message.messageDate()).isEqualTo(NOW);
      assertThat(message.requiredHeader(MessageHeaders.LOGICAL_CHANNEL)).isEqualTo("order-events");
      assertThat(message.requiredHeader(MessageHeaders.DESTINATION))
          .isEqualTo("prod.order-events");
      assertThat(message.type()).isEqualTo("example.v1");
      assertThat(message.partitionId()).isEqualTo("order-1");
    });
  }

  @Test
  void preservesACallerSuppliedIdentityAndDate() {
    CapturingImplementation implementation = new CapturingImplementation();
    MessageProducerImpl producer = producer(implementation, List.of());
    UUID suppliedId = UUID.randomUUID();
    Instant suppliedDate = NOW.minusSeconds(60);

    producer.send("order-events", MessageBuilder.from(baseMessage())
        .withId(suppliedId)
        .withMessageDate(suppliedDate)
        .build());

    assertThat(implementation.messages).singleElement().satisfies(message -> {
      assertThat(message.id()).isEqualTo(suppliedId);
      assertThat(message.messageDate()).isEqualTo(suppliedDate);
    });
  }

  @Test
  void runsPreHooksInOrderAndPostHooksInReverse() {
    CapturingImplementation implementation = new CapturingImplementation();
    List<String> calls = new ArrayList<>();
    MessageInterceptor first = interceptor("first", calls);
    MessageInterceptor second = interceptor("second", calls);
    MessageProducerImpl producer = producer(implementation, List.of(first, second));

    producer.send("order-events", baseMessage());

    assertThat(calls).containsExactly(
        "first.pre", "second.pre", "second.post.success", "first.post.success");
    assertThat(implementation.messages).singleElement().satisfies(message ->
        assertThat(message.headers())
            .containsEntry("first", "present")
            .containsEntry("second", "present"));
  }

  @Test
  void reportsTheOriginalDeliveryFailureWithoutMaskingIt() {
    IllegalStateException deliveryFailure = new IllegalStateException("outbox unavailable");
    CapturingImplementation implementation = new CapturingImplementation();
    implementation.failure = deliveryFailure;
    List<Throwable> observedFailures = new ArrayList<>();
    MessageInterceptor interceptor = new MessageInterceptor() {
      @Override
      public void postSend(Message message, Throwable failure) {
        observedFailures.add(failure);
        throw new IllegalArgumentException("observation failed");
      }
    };

    assertThatThrownBy(() -> producer(implementation, List.of(interceptor))
        .send("order-events", baseMessage()))
        .isSameAs(deliveryFailure)
        .satisfies(exception -> assertThat(exception.getSuppressed())
            .extracting(Throwable::getMessage)
            .containsExactly("observation failed"));
    assertThat(observedFailures).containsExactly(deliveryFailure);
  }

  @Test
  void rejectsAConflictingMappedDestinationBeforeDelivery() {
    CapturingImplementation implementation = new CapturingImplementation();
    Message conflicting = MessageBuilder.from(baseMessage())
        .withHeader(MessageHeaders.DESTINATION, "caller-forged-topic")
        .build();

    assertThatThrownBy(() -> producer(implementation, List.of())
        .send("order-events", conflicting))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Reserved message header conflict: destination");
    assertThat(implementation.messages).isEmpty();
  }

  @Test
  void rejectsAnInterceptorThatChangesReservedIdentity() {
    MessageInterceptor interceptor = new MessageInterceptor() {
      @Override
      public Message preSend(Message message) {
        return message.withHeader(MessageHeaders.MESSAGE_ID, UUID.randomUUID().toString());
      }
    };

    assertThatThrownBy(() -> producer(new CapturingImplementation(), List.of(interceptor))
        .send("order-events", baseMessage()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("MessageInterceptor changed reserved header: message-id");
  }

  @Test
  void rejectsMissingGenericTypeAndPartitionBeforeDelivery() {
    CapturingImplementation implementation = new CapturingImplementation();
    Message incomplete = MessageBuilder.withPayload("{}").build();

    assertThatThrownBy(() -> producer(implementation, List.of())
        .send("order-events", incomplete))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Missing message header: message-type");
    assertThat(implementation.messages).isEmpty();
  }

  private MessageProducerImpl producer(
      MessageProducerImplementation implementation,
      List<MessageInterceptor> interceptors
  ) {
    return new MessageProducerImpl(
        implementation,
        new MapBasedChannelMapping(Map.of("order-events", "prod.order-events")),
        interceptors,
        () -> GENERATED_ID,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private Message baseMessage() {
    return MessageBuilder.withPayload("{}")
        .withType("example.v1")
        .withPartitionId("order-1")
        .build();
  }

  private MessageInterceptor interceptor(String name, List<String> calls) {
    return new MessageInterceptor() {
      @Override
      public Message preSend(Message message) {
        calls.add(name + ".pre");
        return message.withHeader(name, "present");
      }

      @Override
      public void postSend(Message message, Throwable failure) {
        calls.add(name + ".post." + (failure == null ? "success" : "failure"));
      }
    };
  }

  private static final class CapturingImplementation implements MessageProducerImplementation {
    private final List<Message> messages = new ArrayList<>();
    private String destination;
    private RuntimeException failure;

    @Override
    public void send(String destination, Message message) {
      this.destination = destination;
      messages.add(message);
      if (failure != null) {
        throw failure;
      }
    }
  }
}
