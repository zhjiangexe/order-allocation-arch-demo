package com.flowzati.archone.messaging.consumer.common;

/** Stable, transport-neutral categories used to explain message processing failures. */
public enum MessageFailureCategory {
    MAPPING,
    CONTRACT,
    HANDLER,
    INFRASTRUCTURE
}
