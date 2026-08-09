package com.flowzati.archone.messaging.spring.consumer.kafka;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;

/** Immutable subscriber-specific Spring Kafka operational policy. */
public final class KafkaSubscriptionPolicy {

  private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);
  private static final KafkaSubscriptionPolicy DEFAULTS = builder().build();

  private final int concurrency;
  private final ContainerProperties.AckMode ackMode;
  private final boolean missingTopicsFatal;
  private final Duration shutdownTimeout;
  private final CommonErrorHandler commonErrorHandler;

  private KafkaSubscriptionPolicy(Builder builder) {
    this.concurrency = builder.concurrency;
    this.ackMode = builder.ackMode;
    this.missingTopicsFatal = builder.missingTopicsFatal;
    this.shutdownTimeout = builder.shutdownTimeout;
    this.commonErrorHandler = builder.commonErrorHandler;
  }

  public static KafkaSubscriptionPolicy defaults() {
    return DEFAULTS;
  }

  public static Builder builder() {
    return new Builder();
  }

  public int concurrency() {
    return concurrency;
  }

  public ContainerProperties.AckMode ackMode() {
    return ackMode;
  }

  public boolean missingTopicsFatal() {
    return missingTopicsFatal;
  }

  public Duration shutdownTimeout() {
    return shutdownTimeout;
  }

  public Optional<CommonErrorHandler> commonErrorHandler() {
    return Optional.ofNullable(commonErrorHandler);
  }

  /** Mutable construction step; {@link #build()} returns an immutable policy. */
  public static final class Builder {

    private int concurrency = 1;
    private ContainerProperties.AckMode ackMode = ContainerProperties.AckMode.BATCH;
    private boolean missingTopicsFatal = true;
    private Duration shutdownTimeout = DEFAULT_SHUTDOWN_TIMEOUT;
    private CommonErrorHandler commonErrorHandler;

    private Builder() {
    }

    public Builder concurrency(int concurrency) {
      if (concurrency < 1) {
        throw new IllegalArgumentException("Kafka subscription concurrency must be positive");
      }
      this.concurrency = concurrency;
      return this;
    }

    public Builder ackMode(ContainerProperties.AckMode ackMode) {
      this.ackMode = Objects.requireNonNull(ackMode, "Kafka acknowledgement mode is required");
      return this;
    }

    public Builder missingTopicsFatal(boolean missingTopicsFatal) {
      this.missingTopicsFatal = missingTopicsFatal;
      return this;
    }

    public Builder shutdownTimeout(Duration shutdownTimeout) {
      Objects.requireNonNull(shutdownTimeout, "Kafka shutdown timeout is required");
      if (shutdownTimeout.isNegative() || shutdownTimeout.isZero()) {
        throw new IllegalArgumentException("Kafka shutdown timeout must be positive");
      }
      this.shutdownTimeout = shutdownTimeout;
      return this;
    }

    public Builder commonErrorHandler(CommonErrorHandler commonErrorHandler) {
      this.commonErrorHandler = Objects.requireNonNull(
          commonErrorHandler, "Kafka error handler is required");
      return this;
    }

    public KafkaSubscriptionPolicy build() {
      return new KafkaSubscriptionPolicy(this);
    }
  }
}
