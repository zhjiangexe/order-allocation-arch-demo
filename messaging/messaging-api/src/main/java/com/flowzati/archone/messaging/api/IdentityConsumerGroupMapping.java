package com.flowzati.archone.messaging.api;

/** Default mapping that keeps the consumer group equal to the stable subscriber ID. */
public enum IdentityConsumerGroupMapping implements ConsumerGroupMapping {
  INSTANCE;

  @Override
  public String transform(String subscriberId) {
    if (subscriberId == null || subscriberId.isBlank()) {
      throw new IllegalArgumentException("Subscriber ID is required");
    }
    return subscriberId;
  }
}
