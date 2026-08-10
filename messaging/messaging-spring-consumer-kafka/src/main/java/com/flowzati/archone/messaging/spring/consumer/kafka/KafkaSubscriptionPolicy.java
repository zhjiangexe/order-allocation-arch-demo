package com.flowzati.archone.messaging.spring.consumer.kafka;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;

/** Immutable subscriber-specific overrides applied after the shared Kafka factory baseline. */
public final class KafkaSubscriptionPolicy {

  private static final KafkaSubscriptionPolicy DEFAULTS = builder().build();

  private final Integer concurrency;
  private final ContainerProperties.AckMode ackMode;
  private final Boolean missingTopicsFatal;
  private final Boolean observationEnabled;
  private final Boolean autoStartup;
  private final Duration shutdownTimeout;
  private final CommonErrorHandler commonErrorHandler;

  private KafkaSubscriptionPolicy(Builder builder) {
    this.concurrency = builder.concurrency;
    this.ackMode = builder.ackMode;
    this.missingTopicsFatal = builder.missingTopicsFatal;
    this.observationEnabled = builder.observationEnabled;
    this.autoStartup = builder.autoStartup;
    this.shutdownTimeout = builder.shutdownTimeout;
    this.commonErrorHandler = builder.commonErrorHandler;
  }

  /** Returns an empty override policy that inherits every setting from the shared factory. */
  public static KafkaSubscriptionPolicy defaults() {
    return DEFAULTS;
  }

  public static Builder builder() {
    return new Builder();
  }

  public Optional<Integer> concurrencyOverride() {
    return Optional.ofNullable(concurrency);
  }

  public Optional<ContainerProperties.AckMode> ackModeOverride() {
    return Optional.ofNullable(ackMode);
  }

  public Optional<Boolean> missingTopicsFatalOverride() {
    return Optional.ofNullable(missingTopicsFatal);
  }

  public Optional<Boolean> observationEnabledOverride() {
    return Optional.ofNullable(observationEnabled);
  }

  public Optional<Boolean> autoStartupOverride() {
    return Optional.ofNullable(autoStartup);
  }

  public Optional<Duration> shutdownTimeoutOverride() {
    return Optional.ofNullable(shutdownTimeout);
  }

  public Optional<CommonErrorHandler> commonErrorHandler() {
    return Optional.ofNullable(commonErrorHandler);
  }

  /** Mutable construction step; {@link #build()} returns an immutable policy. */
  public static final class Builder {

    private Integer concurrency;
    private ContainerProperties.AckMode ackMode;
    private Boolean missingTopicsFatal;
    private Boolean observationEnabled;
    private Boolean autoStartup;
    private Duration shutdownTimeout;
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

    /** Enables Spring Kafka transport observations and disables its legacy listener timers. */
    public Builder observationEnabled(boolean observationEnabled) {
      this.observationEnabled = observationEnabled;
      return this;
    }

    /** Controls whether subscribe-on-make starts the programmatic container immediately. */
    public Builder autoStartup(boolean autoStartup) {
      this.autoStartup = autoStartup;
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
