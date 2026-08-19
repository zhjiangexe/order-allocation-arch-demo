package com.flowzati.archone.messaging.spring.consumer.kafka;

/** Archone-owned DLT headers that complement Spring Kafka's original-record metadata. */
public final class KafkaDeadLetterHeaders {

    public static final String ORIGINAL_LOGICAL_CHANNEL = "archone-dlt-original-logical-channel";
    public static final String ORIGINAL_PHYSICAL_DESTINATION = "archone-dlt-original-physical-destination";
    public static final String SUBSCRIBER_ID = "archone-dlt-subscriber-id";
    public static final String CONSUMER_GROUP_ID = "archone-dlt-consumer-group-id";

    private KafkaDeadLetterHeaders() {}
}
