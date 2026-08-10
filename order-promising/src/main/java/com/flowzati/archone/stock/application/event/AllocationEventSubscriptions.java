package com.flowzati.archone.stock.application.event;

/** Stable identities of event subscriptions owned by the Allocation bounded context. */
public final class AllocationEventSubscriptions {

  /** Receives order lifecycle facts from Ordering. */
  public static final String ORDER_LIFECYCLE = "allocation-ordering-events";

  /** Kafka offset scope for {@link #ORDER_LIFECYCLE}. */
  public static final String ORDER_LIFECYCLE_CONSUMER_GROUP =
      "allocation-ordering-events";

  /** Receives physical availability facts from Inventory. */
  public static final String INVENTORY_AVAILABILITY = "allocation-inventory-events";

  /** Kafka offset scope for {@link #INVENTORY_AVAILABILITY}. */
  public static final String INVENTORY_AVAILABILITY_CONSUMER_GROUP =
      "allocation-inventory-events";

  private AllocationEventSubscriptions() {
  }
}
