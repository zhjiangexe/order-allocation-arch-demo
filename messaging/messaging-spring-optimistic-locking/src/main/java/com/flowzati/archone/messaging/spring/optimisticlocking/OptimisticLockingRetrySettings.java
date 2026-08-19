package com.flowzati.archone.messaging.spring.optimisticlocking;

import java.time.Duration;
import java.util.Objects;

/** Bounded local retry settings applied before broker redelivery is considered. */
public record OptimisticLockingRetrySettings(int maxRetries, Duration delay) {

    public static final int DEFAULT_MAX_RETRIES = 2;
    public static final Duration DEFAULT_DELAY = Duration.ofMillis(100);

    public OptimisticLockingRetrySettings {
        Objects.requireNonNull(delay, "Optimistic-lock retry delay is required");
        if (maxRetries < 0 || delay.isNegative()) {
            throw new IllegalArgumentException("Optimistic-lock retry settings are invalid");
        }
    }

    public static OptimisticLockingRetrySettings defaults() {
        return new OptimisticLockingRetrySettings(DEFAULT_MAX_RETRIES, DEFAULT_DELAY);
    }
}
