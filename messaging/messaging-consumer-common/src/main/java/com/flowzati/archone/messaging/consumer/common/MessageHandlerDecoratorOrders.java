package com.flowzati.archone.messaging.consumer.common;

/**
 * Built-in ordering contract. Lower values wrap higher values.
 *
 * <p>Observation and interceptor lifecycle must see the final transaction outcome. Transactional
 * idempotency must wrap protocol dispatch and the application handler.
 */
public final class MessageHandlerDecoratorOrders {

  public static final int OBSERVATION = 1_000;
  public static final int INTERCEPTOR_LIFECYCLE = 1_100;
  public static final int TRANSACTIONAL_IDEMPOTENCY = 2_000;
  public static final int PROTOCOL_DISPATCH = 3_000;
  public static final int APPLICATION_CUSTOM_MIN = 4_000;
  public static final int APPLICATION_CUSTOM_MAX = 4_999;

  private MessageHandlerDecoratorOrders() {
  }
}
