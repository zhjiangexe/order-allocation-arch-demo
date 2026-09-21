package com.flowzati.archone.messaging.api;

import java.util.Optional;

/**
 * Transport-neutral optional settings for one message subscription.
 *
 * <p>The basic Tram-shaped API defaults the broker delivery identity to the stable subscriber ID.
 * Transport-specific settings such as Kafka concurrency, acknowledgement, retry, and DLT belong
 * to the transport runtime rather than this generic contract.
 */
public final class MessageSubscriptionOptions {

    private static final MessageSubscriptionOptions DEFAULTS = new MessageSubscriptionOptions(null);

    private final String consumerGroupId;

    private MessageSubscriptionOptions(String consumerGroupId) {
        if (consumerGroupId != null && consumerGroupId.isBlank()) {
            throw new IllegalArgumentException("Message consumer group ID must not be blank");
        }
        this.consumerGroupId = consumerGroupId;
    }

    public static MessageSubscriptionOptions defaults() {
        return DEFAULTS;
    }

    public static MessageSubscriptionOptions withConsumerGroupId(String consumerGroupId) {
        return builder().consumerGroupId(consumerGroupId).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Optional<String> consumerGroupId() {
        return Optional.ofNullable(consumerGroupId);
    }

    public String resolveConsumerGroupId(String subscriberId) {
        if (subscriberId == null || subscriberId.isBlank()) {
            throw new IllegalArgumentException("Message subscriber ID is required");
        }
        return consumerGroupId().orElse(subscriberId);
    }

    /** Mutable construction step; {@link #build()} returns an immutable options value. */
    public static final class Builder {

        private String consumerGroupId;

        private Builder() {}

        public Builder consumerGroupId(String consumerGroupId) {
            if (consumerGroupId == null || consumerGroupId.isBlank()) {
                throw new IllegalArgumentException("Message consumer group ID is required");
            }
            this.consumerGroupId = consumerGroupId;
            return this;
        }

        public MessageSubscriptionOptions build() {
            if (consumerGroupId == null) {
                return MessageSubscriptionOptions.defaults();
            }
            return new MessageSubscriptionOptions(consumerGroupId);
        }
    }
}
