package com.flowzati.archone.messaging.events;

import com.flowzati.archone.messaging.api.MessageSubscriptionOptions;
import java.util.Objects;
import java.util.Optional;

/** Typed dispatch policy plus the additive generic subscription identity options. */
public final class IntegrationEventDispatcherOptions {

  private final MessageSubscriptionOptions subscriptionOptions;
  private final UnhandledEventPolicy unhandledEventPolicy;
  private final UnhandledIntegrationEventObserver unhandledEventObserver;

  private IntegrationEventDispatcherOptions(Builder builder) {
    this.subscriptionOptions = builder.subscriptionOptions;
    this.unhandledEventPolicy = builder.unhandledEventPolicy;
    this.unhandledEventObserver = builder.unhandledEventObserver;
  }

  public static IntegrationEventDispatcherOptions strict() {
    return builder().build();
  }

  public static Builder builder() {
    return new Builder();
  }

  public MessageSubscriptionOptions subscriptionOptions() {
    return subscriptionOptions;
  }

  public UnhandledEventPolicy unhandledEventPolicy() {
    return unhandledEventPolicy;
  }

  public Optional<UnhandledIntegrationEventObserver> unhandledEventObserver() {
    return Optional.ofNullable(unhandledEventObserver);
  }

  /** Mutable construction step; {@link #build()} returns immutable dispatcher options. */
  public static final class Builder {

    private MessageSubscriptionOptions subscriptionOptions =
        MessageSubscriptionOptions.defaults();
    private UnhandledEventPolicy unhandledEventPolicy = UnhandledEventPolicy.FAIL;
    private UnhandledIntegrationEventObserver unhandledEventObserver;

    private Builder() {
    }

    public Builder subscriptionOptions(MessageSubscriptionOptions subscriptionOptions) {
      this.subscriptionOptions = Objects.requireNonNull(
          subscriptionOptions, "Message subscription options are required");
      return this;
    }

    /** IGNORE is accepted only with an observer, so the policy can never become silent loss. */
    public Builder ignoreUnhandledEventsWith(UnhandledIntegrationEventObserver observer) {
      this.unhandledEventPolicy = UnhandledEventPolicy.IGNORE_WITH_METRIC;
      this.unhandledEventObserver = Objects.requireNonNull(
          observer, "Unhandled Integration Event observer is required");
      return this;
    }

    public IntegrationEventDispatcherOptions build() {
      return new IntegrationEventDispatcherOptions(this);
    }
  }
}
