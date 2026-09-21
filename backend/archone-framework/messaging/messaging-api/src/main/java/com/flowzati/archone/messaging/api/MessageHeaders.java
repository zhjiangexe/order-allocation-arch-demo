package com.flowzati.archone.messaging.api;

import java.util.Set;
import java.util.regex.Pattern;

/** Standard headers shared by all messaging protocols. */
public final class MessageHeaders {

    public static final String MESSAGE_ID = "message-id";
    public static final String MESSAGE_TYPE = "message-type";
    public static final String LOGICAL_CHANNEL = "logical-channel";
    public static final String DESTINATION = "destination";
    public static final String PARTITION_ID = "partition-id";
    public static final String MESSAGE_DATE = "message-date";
    public static final String CORRELATION_ID = "correlation-id";
    public static final String CAUSATION_ID = "causation-id";
    public static final String TRACEPARENT = "traceparent";
    public static final String TRACESTATE = "tracestate";
    public static final String CONTENT_TYPE = "content-type";
    public static final String APPLICATION_JSON = "application/json";

    private static final Pattern VALID_NAME = Pattern.compile("[a-z0-9][a-z0-9._-]*");
    private static final Set<String> PRODUCER_RESERVED =
            Set.of(MESSAGE_ID, MESSAGE_TYPE, LOGICAL_CHANNEL, DESTINATION, PARTITION_ID, MESSAGE_DATE, CONTENT_TYPE);

    private MessageHeaders() {}

    public static boolean isProducerReserved(String name) {
        return PRODUCER_RESERVED.contains(name);
    }

    public static void require(Message message, String... names) {
        if (message == null) {
            throw new IllegalArgumentException("Message is required");
        }
        for (String name : names) {
            message.requiredHeader(name);
        }
    }

    /** Validates a header independently of a {@link Message} instance. */
    public static void validate(String name, String value) {
        validateName(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Message header value is required: " + name);
        }
    }

    /** Validates the canonical lower-case header-name syntax. */
    public static void validateName(String name) {
        if (name == null || !VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid message header name: " + name);
        }
    }
}
