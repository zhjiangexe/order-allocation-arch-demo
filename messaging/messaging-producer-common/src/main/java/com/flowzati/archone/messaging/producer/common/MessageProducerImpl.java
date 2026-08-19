package com.flowzati.archone.messaging.producer.common;

import com.flowzati.archone.messaging.api.ChannelMapping;
import com.flowzati.archone.messaging.api.IdentityChannelMapping;
import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageBuilder;
import com.flowzati.archone.messaging.api.MessageHeaders;
import com.flowzati.archone.messaging.api.MessageIdGenerator;
import com.flowzati.archone.messaging.api.MessageInterceptor;
import com.flowzati.archone.messaging.api.MessageProducer;
import com.flowzati.archone.messaging.api.MessagePublicationContext;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Framework-neutral producer orchestration aligned with Eventuate Tram's responsibility split.
 *
 * <p>It maps the logical channel, normalizes required transport headers, runs immutable
 * interceptor hooks, and delegates exactly once to the single producer implementation SPI. It
 * contains no Outbox, SQL, transaction, or broker type.
 */
public final class MessageProducerImpl implements MessageProducer {

    private final MessageProducerImplementation implementation;
    private final ChannelMapping channelMapping;
    private final List<MessageInterceptor> interceptors;
    private final MessageIdGenerator messageIdGenerator;
    private final Clock clock;

    public MessageProducerImpl(MessageProducerImplementation implementation) {
        this(
                implementation,
                IdentityChannelMapping.INSTANCE,
                List.of(),
                new RandomUuidMessageIdGenerator(),
                Clock.systemUTC());
    }

    public MessageProducerImpl(
            MessageProducerImplementation implementation,
            ChannelMapping channelMapping,
            List<MessageInterceptor> interceptors,
            MessageIdGenerator messageIdGenerator,
            Clock clock) {
        this.implementation = Objects.requireNonNull(implementation, "Message producer implementation is required");
        this.channelMapping = Objects.requireNonNull(channelMapping, "Channel mapping is required");
        this.messageIdGenerator = Objects.requireNonNull(messageIdGenerator, "Message ID generator is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
        if (interceptors == null || interceptors.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Message interceptors are required");
        }
        this.interceptors = List.copyOf(interceptors);
    }

    @Override
    public void send(String logicalChannel, Message message) {
        if (logicalChannel == null || logicalChannel.isBlank() || message == null) {
            throw new IllegalArgumentException("Logical channel and message are required");
        }

        String destination = channelMapping.transform(logicalChannel);
        if (destination == null || destination.isBlank()) {
            throw new IllegalArgumentException(
                    "Channel mapping returned an invalid destination for: " + logicalChannel);
        }

        Message current = normalize(logicalChannel, destination, message);
        MessagePublicationContext publicationContext = new MessagePublicationContext(logicalChannel, destination);
        List<MessageInterceptor> invokedInterceptors = new ArrayList<>(interceptors.size());
        Throwable failure = null;

        try {
            for (MessageInterceptor interceptor : interceptors) {
                Message intercepted = interceptor.preSend(current, publicationContext);
                if (intercepted == null) {
                    throw new IllegalStateException("MessageInterceptor.preSend returned null");
                }
                assertReservedHeadersUnchanged(current, intercepted);
                current = intercepted;
                invokedInterceptors.add(interceptor);
            }
            validateNormalized(current);
            implementation.send(destination, current);
        } catch (RuntimeException | Error exception) {
            failure = exception;
        }

        Throwable postSendFailure = invokePostSendInReverse(invokedInterceptors, current, publicationContext, failure);
        if (failure != null) {
            if (postSendFailure != null) {
                failure.addSuppressed(postSendFailure);
            }
            rethrow(failure);
        }
        if (postSendFailure != null) {
            rethrow(postSendFailure);
        }
    }

    private Message normalize(String logicalChannel, String destination, Message message) {
        MessageBuilder builder = MessageBuilder.from(message);
        putOrValidate(builder, message, MessageHeaders.LOGICAL_CHANNEL, logicalChannel);
        putOrValidate(builder, message, MessageHeaders.DESTINATION, destination);
        if (message.header(MessageHeaders.MESSAGE_ID).isEmpty()) {
            UUID generatedId = messageIdGenerator.generate();
            if (generatedId == null) {
                throw new IllegalStateException("MessageIdGenerator returned null");
            }
            builder.withId(generatedId);
        }
        if (message.header(MessageHeaders.MESSAGE_DATE).isEmpty()) {
            builder.withMessageDate(clock.instant());
        }
        Message normalized = builder.build();
        validateNormalized(normalized);
        return normalized;
    }

    private void putOrValidate(MessageBuilder builder, Message message, String header, String normalizedValue) {
        message.header(header).ifPresent(existing -> {
            if (!existing.equals(normalizedValue)) {
                throw new IllegalArgumentException("Reserved message header conflict: " + header);
            }
        });
        builder.withHeader(header, normalizedValue);
    }

    private void validateNormalized(Message message) {
        MessageHeaders.require(
                message,
                MessageHeaders.MESSAGE_ID,
                MessageHeaders.MESSAGE_TYPE,
                MessageHeaders.LOGICAL_CHANNEL,
                MessageHeaders.DESTINATION,
                MessageHeaders.PARTITION_ID,
                MessageHeaders.MESSAGE_DATE,
                MessageHeaders.CONTENT_TYPE);
        message.id();
        message.messageDate();
    }

    private void assertReservedHeadersUnchanged(Message before, Message after) {
        for (String header : before.headers().keySet()) {
            if (MessageHeaders.isProducerReserved(header)
                    && !before.headers().get(header).equals(after.headers().get(header))) {
                throw new IllegalArgumentException("MessageInterceptor changed reserved header: " + header);
            }
        }
    }

    private Throwable invokePostSendInReverse(
            List<MessageInterceptor> invokedInterceptors,
            Message message,
            MessagePublicationContext publicationContext,
            Throwable deliveryFailure) {
        Throwable firstFailure = null;
        for (int index = invokedInterceptors.size() - 1; index >= 0; index--) {
            try {
                invokedInterceptors.get(index).postSend(message, publicationContext, deliveryFailure);
            } catch (RuntimeException | Error exception) {
                if (firstFailure == null) {
                    firstFailure = exception;
                } else {
                    firstFailure.addSuppressed(exception);
                }
            }
        }
        return firstFailure;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw (Error) failure;
    }
}
