package com.flowzati.archone.messaging.spring.consumer.kafka;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;

/** Idempotent lifecycle handle for one Spring Kafka container. */
final class DefaultKafkaMessageSubscription implements KafkaMessageSubscription {

    private final KafkaSubscriptionRegistration registration;
    private final ConcurrentMessageListenerContainer<String, String> container;
    private final Runnable release;
    private final AtomicBoolean stopped = new AtomicBoolean();

    DefaultKafkaMessageSubscription(
            KafkaSubscriptionRegistration registration,
            ConcurrentMessageListenerContainer<String, String> container,
            Runnable release) {
        this.registration = registration;
        this.container = container;
        this.release = release;
    }

    @Override
    public String containerId() {
        return registration.containerId();
    }

    @Override
    public String subscriberId() {
        return registration.subscriberId();
    }

    @Override
    public String consumerGroupId() {
        return registration.consumerGroupId();
    }

    @Override
    public Set<String> destinations() {
        return registration.destinations();
    }

    @Override
    public boolean isRunning() {
        return !stopped.get() && container.isRunning();
    }

    @Override
    public boolean isReady() {
        return isRunning()
                && !container.getContainers().isEmpty()
                && container.getContainers().stream().allMatch(child -> child.isRunning());
    }

    @Override
    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        try {
            container.stop();
        } finally {
            release.run();
        }
    }

    ConcurrentMessageListenerContainer<String, String> container() {
        return container;
    }
}
