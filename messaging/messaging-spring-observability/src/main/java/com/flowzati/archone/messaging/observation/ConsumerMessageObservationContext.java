package com.flowzati.archone.messaging.observation;

import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageContext;
import io.micrometer.observation.transport.ReceiverContext;
import java.util.Objects;

/** Observation and trace-extraction state for one semantic message processing attempt. */
public final class ConsumerMessageObservationContext extends ReceiverContext<Message> {

  private final MessageContext messageContext;
  private MessagingObservationOutcome outcome = MessagingObservationOutcome.UNKNOWN;

  public ConsumerMessageObservationContext(Message message, MessageContext messageContext) {
    super((carrier, key) -> carrier == null
        ? null
        : carrier.header(MutableMessageCarrier.normalize(key)).orElse(null));
    this.messageContext = Objects.requireNonNull(messageContext, "Message context is required");
    setCarrier(Objects.requireNonNull(message, "Message is required"));
  }

  public MessageContext messageContext() {
    return messageContext;
  }

  public Message message() {
    return Objects.requireNonNull(getCarrier(), "Message carrier is required");
  }

  public MessagingObservationOutcome outcome() {
    return outcome;
  }

  public void recordOutcome(MessagingObservationOutcome outcome) {
    this.outcome = Objects.requireNonNull(outcome, "Messaging observation outcome is required");
  }
}
