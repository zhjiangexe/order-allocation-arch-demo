package com.flowzati.archone.messaging.testsupport;

import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageContext;
import com.flowzati.archone.messaging.api.MessageHandler;
import com.flowzati.archone.messaging.api.MessageSubscription;
import com.flowzati.archone.messaging.consumer.common.MessageConsumerImplementation;
import com.flowzati.archone.messaging.consumer.common.ResolvedMessageSubscription;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** In-memory consumer runtime probe that can deliver a message to an explicitly selected subscriber. */
public final class ControllableMessageConsumerImplementation
    implements MessageConsumerImplementation {

  private final List<Registration> registrations = new CopyOnWriteArrayList<>();

  @Override
  public MessageSubscription subscribe(
      ResolvedMessageSubscription subscription,
      MessageHandler handler
  ) {
    TestSubscription handle = new TestSubscription();
    registrations.add(new Registration(subscription, handler, handle));
    return handle;
  }

  public List<ResolvedMessageSubscription> subscriptions() {
    return registrations.stream().map(Registration::subscription).toList();
  }

  public void emit(
      String subscriberId,
      String destination,
      Message message,
      int processingAttempt
  ) {
    Registration registration = registrations.stream()
        .filter(candidate -> candidate.subscription().subscriberId().equals(subscriberId))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown test subscriber: " + subscriberId));
    if (!registration.handle().isRunning()) {
      throw new IllegalStateException("Test subscription is stopped: " + subscriberId);
    }
    registration.handler().handle(message, new MessageContext(
        subscriberId,
        registration.subscription().logicalChannelFor(destination),
        processingAttempt));
  }

  private record Registration(
      ResolvedMessageSubscription subscription,
      MessageHandler handler,
      TestSubscription handle
  ) {
  }

  private static final class TestSubscription implements MessageSubscription {
    private final AtomicBoolean running = new AtomicBoolean(true);

    @Override
    public boolean isRunning() {
      return running.get();
    }

    @Override
    public void stop() {
      running.set(false);
    }
  }
}
