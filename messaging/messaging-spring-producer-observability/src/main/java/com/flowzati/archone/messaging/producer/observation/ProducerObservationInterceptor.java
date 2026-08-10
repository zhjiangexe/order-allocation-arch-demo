package com.flowzati.archone.messaging.producer.observation;

import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageInterceptor;
import com.flowzati.archone.messaging.api.MessagePublicationContext;
import com.flowzati.archone.messaging.observation.DefaultProducerMessageObservationConvention;
import com.flowzati.archone.messaging.observation.MessagingObservationOutcome;
import com.flowzati.archone.messaging.observation.ProducerMessageObservationContext;
import com.flowzati.archone.messaging.observation.ProducerMessageObservationConvention;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * Observes the existing synchronous producer lifecycle; it does not publish or persist messages.
 *
 * <p>A stack, rather than a single thread-local slot, preserves correct scopes for nested sends.
 * The producer orchestration invokes {@code preSend} and {@code postSend} on the same thread.
 */
public final class ProducerObservationInterceptor implements MessageInterceptor {

  private static final ProducerMessageObservationConvention DEFAULT_CONVENTION =
      new DefaultProducerMessageObservationConvention();

  private final ObservationRegistry observationRegistry;
  private final ProducerMessageObservationConvention customConvention;
  private final ThreadLocal<Deque<ObservationAttempt>> attempts =
      ThreadLocal.withInitial(ArrayDeque::new);

  public ProducerObservationInterceptor(ObservationRegistry observationRegistry) {
    this(observationRegistry, null);
  }

  public ProducerObservationInterceptor(
      ObservationRegistry observationRegistry,
      ProducerMessageObservationConvention customConvention
  ) {
    this.observationRegistry = Objects.requireNonNull(
        observationRegistry, "Observation registry is required");
    this.customConvention = customConvention;
  }

  @Override
  public Message preSend(Message message, MessagePublicationContext publicationContext) {
    ProducerMessageObservationContext observationContext =
        new ProducerMessageObservationContext(message, publicationContext);
    Observation observation = Observation.createNotStarted(
        customConvention,
        DEFAULT_CONVENTION,
        () -> observationContext,
        observationRegistry);

    observation.start();
    Observation.Scope scope;
    try {
      scope = observation.openScope();
    } catch (RuntimeException | Error exception) {
      observation.error(exception);
      observation.stop();
      throw exception;
    }
    attempts.get().push(new ObservationAttempt(observation, scope, observationContext));
    return observationContext.message();
  }

  @Override
  public void postSend(
      Message message,
      MessagePublicationContext publicationContext,
      Throwable failure
  ) {
    Deque<ObservationAttempt> currentAttempts = attempts.get();
    ObservationAttempt attempt = currentAttempts.poll();
    if (currentAttempts.isEmpty()) {
      attempts.remove();
    }
    if (attempt == null) {
      throw new IllegalStateException("Producer observation postSend has no matching preSend");
    }

    if (failure == null) {
      attempt.context().recordOutcome(MessagingObservationOutcome.APPENDED);
    } else {
      attempt.context().recordOutcome(MessagingObservationOutcome.FAILED);
      attempt.observation().error(failure);
    }

    try {
      attempt.scope().close();
    } finally {
      attempt.observation().stop();
    }
  }

  private record ObservationAttempt(
      Observation observation,
      Observation.Scope scope,
      ProducerMessageObservationContext context
  ) {
  }
}
