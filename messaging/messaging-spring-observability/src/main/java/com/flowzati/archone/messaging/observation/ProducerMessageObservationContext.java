package com.flowzati.archone.messaging.observation;

import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessagePublicationContext;
import io.micrometer.observation.transport.SenderContext;
import java.util.Objects;

/** Observation and propagation state for one synchronous Outbox append attempt. */
public final class ProducerMessageObservationContext extends SenderContext<MutableMessageCarrier> {

  private final MessagePublicationContext publicationContext;
  private MessagingObservationOutcome outcome = MessagingObservationOutcome.UNKNOWN;

  public ProducerMessageObservationContext(
      Message message,
      MessagePublicationContext publicationContext
  ) {
    super((carrier, key, value) -> {
      if (carrier != null) {
        carrier.setHeader(key, value);
      }
    });
    this.publicationContext = Objects.requireNonNull(
        publicationContext, "Message publication context is required");
    setCarrier(new MutableMessageCarrier(message));
  }

  public MessagePublicationContext publicationContext() {
    return publicationContext;
  }

  public Message message() {
    return Objects.requireNonNull(getCarrier(), "Message carrier is required").message();
  }

  public MessagingObservationOutcome outcome() {
    return outcome;
  }

  public void recordOutcome(MessagingObservationOutcome outcome) {
    this.outcome = Objects.requireNonNull(outcome, "Messaging observation outcome is required");
  }
}
