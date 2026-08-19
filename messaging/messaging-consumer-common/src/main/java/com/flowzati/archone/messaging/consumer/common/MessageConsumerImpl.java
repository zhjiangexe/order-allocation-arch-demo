package com.flowzati.archone.messaging.consumer.common;

import com.flowzati.archone.messaging.api.ChannelMapping;
import com.flowzati.archone.messaging.api.ConsumerGroupMapping;
import com.flowzati.archone.messaging.api.IdentityChannelMapping;
import com.flowzati.archone.messaging.api.IdentityConsumerGroupMapping;
import com.flowzati.archone.messaging.api.MessageConsumer;
import com.flowzati.archone.messaging.api.MessageHandler;
import com.flowzati.archone.messaging.api.MessageHandlingOutcome;
import com.flowzati.archone.messaging.api.MessageSubscription;
import com.flowzati.archone.messaging.api.MessageSubscriptionOptions;
import com.flowzati.archone.messaging.api.OutcomeAwareMessageHandler;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Maps logical subscriptions and applies one explicit semantic decorator chain. */
public final class MessageConsumerImpl implements MessageConsumer {

    private final MessageConsumerImplementation implementation;
    private final ChannelMapping channelMapping;
    private final ConsumerGroupMapping consumerGroupMapping;
    private final List<MessageHandlerDecorator> decorators;

    public MessageConsumerImpl(MessageConsumerImplementation implementation) {
        this(implementation, IdentityChannelMapping.INSTANCE, IdentityConsumerGroupMapping.INSTANCE, List.of());
    }

    public MessageConsumerImpl(
            MessageConsumerImplementation implementation,
            ChannelMapping channelMapping,
            List<MessageHandlerDecorator> decorators) {
        this(implementation, channelMapping, IdentityConsumerGroupMapping.INSTANCE, decorators);
    }

    public MessageConsumerImpl(
            MessageConsumerImplementation implementation,
            ChannelMapping channelMapping,
            ConsumerGroupMapping consumerGroupMapping,
            List<MessageHandlerDecorator> decorators) {
        this.implementation = Objects.requireNonNull(implementation, "Message consumer implementation is required");
        this.channelMapping = Objects.requireNonNull(channelMapping, "Channel mapping is required");
        this.consumerGroupMapping = Objects.requireNonNull(consumerGroupMapping, "Consumer group mapping is required");
        if (decorators == null || decorators.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Message handler decorators are required");
        }
        this.decorators = List.copyOf(decorators);
    }

    @Override
    public MessageSubscription subscribe(String subscriberId, Set<String> logicalChannels, MessageHandler handler) {
        return subscribe(subscriberId, logicalChannels, handler, MessageSubscriptionOptions.defaults());
    }

    @Override
    public MessageSubscription subscribe(
            String subscriberId,
            Set<String> logicalChannels,
            MessageHandler handler,
            MessageSubscriptionOptions options) {
        Objects.requireNonNull(options, "Message subscription options are required");
        Objects.requireNonNull(handler, "Message handler is required");
        Set<String> subscribedChannels = validatedChannels(subscriberId, logicalChannels);
        String consumerGroupId = options.consumerGroupId().orElseGet(() -> resolveConsumerGroupId(subscriberId));
        ResolvedMessageSubscription resolved = resolve(subscriberId, consumerGroupId, subscribedChannels);
        MessageHandlerDecoratorChain chain =
                MessageHandlerDecoratorChain.create(decorators, invocation -> invokeTerminal(handler, invocation));

        MessageSubscription subscription = implementation.subscribe(resolved, (message, context) -> {
            if (!subscriberId.equals(context.subscriberId())) {
                throw new IllegalArgumentException("Message context subscriber does not match subscription");
            }
            if (!subscribedChannels.contains(context.logicalChannel())) {
                throw new IllegalArgumentException("Message context channel does not match subscription");
            }
            chain.invokeNext(new MessageHandlerInvocation(message, context));
        });
        return Objects.requireNonNull(subscription, "Message consumer implementation returned null");
    }

    private ProcessingOutcome invokeTerminal(MessageHandler handler, MessageHandlerInvocation invocation) {
        if (handler instanceof OutcomeAwareMessageHandler outcomeAwareHandler) {
            MessageHandlingOutcome outcome = Objects.requireNonNull(
                    outcomeAwareHandler.handleWithOutcome(invocation.message(), invocation.context()),
                    "Outcome-aware message handler returned null");
            return switch (outcome) {
                case PROCESSED -> ProcessingOutcome.PROCESSED;
                case IGNORED_UNHANDLED -> ProcessingOutcome.IGNORED_UNHANDLED;
            };
        }
        handler.handle(invocation.message(), invocation.context());
        return ProcessingOutcome.PROCESSED;
    }

    private String resolveConsumerGroupId(String subscriberId) {
        String consumerGroupId = consumerGroupMapping.transform(subscriberId);
        if (consumerGroupId == null || consumerGroupId.isBlank()) {
            throw new IllegalArgumentException("Consumer group mapping returned an invalid group for: " + subscriberId);
        }
        return consumerGroupId;
    }

    private Set<String> validatedChannels(String subscriberId, Set<String> logicalChannels) {
        if (subscriberId == null
                || subscriberId.isBlank()
                || logicalChannels == null
                || logicalChannels.isEmpty()
                || logicalChannels.stream().anyMatch(channel -> channel == null || channel.isBlank())) {
            throw new IllegalArgumentException("Message subscription fields are required");
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(logicalChannels));
    }

    private ResolvedMessageSubscription resolve(
            String subscriberId, String consumerGroupId, Set<String> logicalChannels) {
        if (consumerGroupId == null || consumerGroupId.isBlank()) {
            throw new IllegalArgumentException("Message subscription fields are required");
        }
        Map<String, String> reverseMapping = new LinkedHashMap<>();
        for (String logicalChannel : logicalChannels) {
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
        return new ResolvedMessageSubscription(subscriberId, consumerGroupId, reverseMapping);
    }
}
