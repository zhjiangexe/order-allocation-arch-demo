package com.flowzati.archone.messaging.consumer.common;

import com.flowzati.archone.messaging.api.ChannelMapping;
import com.flowzati.archone.messaging.api.IdentityChannelMapping;
import com.flowzati.archone.messaging.api.MessageConsumer;
import com.flowzati.archone.messaging.api.MessageHandler;
import com.flowzati.archone.messaging.api.MessageSubscription;
import com.flowzati.archone.messaging.api.MessageSubscriptionConfiguration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Maps logical subscriptions and applies one explicit semantic decorator chain. */
public final class MessageConsumerImpl implements MessageConsumer {

  private final MessageConsumerImplementation implementation;
  private final ChannelMapping channelMapping;
  private final List<MessageHandlerDecorator> decorators;

  public MessageConsumerImpl(MessageConsumerImplementation implementation) {
    this(implementation, IdentityChannelMapping.INSTANCE, List.of());
  }

  public MessageConsumerImpl(
      MessageConsumerImplementation implementation,
      ChannelMapping channelMapping,
      List<MessageHandlerDecorator> decorators
  ) {
    this.implementation = Objects.requireNonNull(
        implementation, "Message consumer implementation is required");
    this.channelMapping = Objects.requireNonNull(channelMapping, "Channel mapping is required");
    if (decorators == null || decorators.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("Message handler decorators are required");
    }
    this.decorators = List.copyOf(decorators);
  }

  @Override
  public MessageSubscription subscribe(
      MessageSubscriptionConfiguration configuration,
      MessageHandler handler
  ) {
    Objects.requireNonNull(configuration, "Message subscription configuration is required");
    Objects.requireNonNull(handler, "Message handler is required");
    ResolvedMessageSubscription resolved = resolve(configuration);
    MessageHandlerDecoratorChain chain = MessageHandlerDecoratorChain.create(
        decorators,
        invocation -> {
          handler.handle(invocation.message(), invocation.context());
          return ProcessingOutcome.PROCESSED;
        });

    MessageSubscription subscription = implementation.subscribe(resolved, (message, context) -> {
      if (!configuration.subscriberId().equals(context.subscriberId())) {
        throw new IllegalArgumentException("Message context subscriber does not match subscription");
      }
      if (!configuration.logicalChannels().contains(context.logicalChannel())) {
        throw new IllegalArgumentException("Message context channel does not match subscription");
      }
      chain.invokeNext(new MessageHandlerInvocation(message, context));
    });
    return Objects.requireNonNull(subscription, "Message consumer implementation returned null");
  }

  private ResolvedMessageSubscription resolve(MessageSubscriptionConfiguration configuration) {
    Map<String, String> reverseMapping = new LinkedHashMap<>();
    for (String logicalChannel : configuration.logicalChannels()) {
      String destination = channelMapping.transform(logicalChannel);
      if (destination == null || destination.isBlank()) {
        throw new IllegalArgumentException(
            "Channel mapping returned an invalid destination for: " + logicalChannel);
      }
      String previous = reverseMapping.putIfAbsent(destination, logicalChannel);
      if (previous != null && !previous.equals(logicalChannel)) {
        throw new IllegalArgumentException(
            "Multiple logical channels map to the same destination: " + destination);
      }
    }
    return new ResolvedMessageSubscription(
        configuration.subscriberId(),
        configuration.consumerGroupId(),
        reverseMapping);
  }
}
