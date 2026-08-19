package com.flowzati.archone.messaging.consumer.common;

import java.util.Objects;

/** Retry decision and diagnostic category for one message processing failure. */
public record MessageFailureClassification(MessageFailureCategory category, boolean retryable) {

    public MessageFailureClassification {
        Objects.requireNonNull(category, "Message failure category is required");
    }

    public static MessageFailureClassification retryable(MessageFailureCategory category) {
        return new MessageFailureClassification(category, true);
    }

    public static MessageFailureClassification nonRetryable(MessageFailureCategory category) {
        return new MessageFailureClassification(category, false);
    }
}
