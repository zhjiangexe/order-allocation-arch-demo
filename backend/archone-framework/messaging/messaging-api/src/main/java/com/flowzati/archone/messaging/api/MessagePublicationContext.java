package com.flowzati.archone.messaging.api;

/** Transport-neutral routing context for one synchronous message publication attempt. */
public record MessagePublicationContext(String logicalChannel, String destination) {

    public MessagePublicationContext {
        if (isBlank(logicalChannel) || isBlank(destination)) {
            throw new IllegalArgumentException("Message publication context fields are required");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
