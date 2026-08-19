package com.flowzati.archone.messaging.spring.consumer.kafka;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Collision-free stable container ID derived only from durable subscription identities. */
final class KafkaContainerIdentity {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private KafkaContainerIdentity() {}

    static String from(String subscriberId, String consumerGroupId) {
        // A dot is outside the Base64 URL alphabet, so the two components cannot become ambiguous.
        return "archone-messaging." + encode(subscriberId) + "." + encode(consumerGroupId);
    }

    private static String encode(String value) {
        return ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
