package com.flowzati.archone.messaging.spring.consumer.kafka;

import com.flowzati.archone.messaging.api.MessageSubscription;
import java.util.Set;

/** Read-only operational identity for one programmatic Kafka subscription. */
public interface KafkaMessageSubscription extends MessageSubscription {

    String containerId();

    String subscriberId();

    String consumerGroupId();

    Set<String> destinations();
}
