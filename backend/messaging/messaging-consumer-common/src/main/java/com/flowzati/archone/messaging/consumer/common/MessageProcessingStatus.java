package com.flowzati.archone.messaging.consumer.common;

/** Stable semantic outcome of one inbound message processing attempt. */
public enum MessageProcessingStatus {
    PROCESSED,
    DUPLICATE,
    IGNORED_UNHANDLED
}
