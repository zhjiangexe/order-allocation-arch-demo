package com.flowzati.archone.common.messaging;

/** Kafka topic names shared by Integration Event producers and consumers. */
public final class IntegrationEventTopics {

  public static final String ORDERING_ORDER_EVENTS_TOPIC = "ordering.order-events";
  public static final String INVENTORY_STOCK_EVENTS_TOPIC = "inventory.stock-events";
  public static final String PROMISING_ALLOCATION_EVENTS_TOPIC = "promising.allocation-events";

  private IntegrationEventTopics() {
  }
}
