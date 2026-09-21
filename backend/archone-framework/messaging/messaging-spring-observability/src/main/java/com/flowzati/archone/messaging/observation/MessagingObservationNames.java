package com.flowzati.archone.messaging.observation;

/** Stable observation names shared by metrics, tracing, and structured logging adapters. */
public final class MessagingObservationNames {

    public static final String PRODUCER = "archone.messaging.producer";
    public static final String CONSUMER = "archone.messaging.consumer";
    public static final String CONSUMER_RETRY = "archone.messaging.consumer.retry";
    public static final String CONSUMER_DLT = "archone.messaging.consumer.dlt";

    private MessagingObservationNames() {}
}
