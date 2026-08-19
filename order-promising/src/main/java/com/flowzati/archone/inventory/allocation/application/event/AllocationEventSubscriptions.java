package com.flowzati.archone.inventory.allocation.application.event;

/** Stable identities of event subscriptions owned by the Allocation bounded context. */
public final class AllocationEventSubscriptions {

  /** Receives order lifecycle facts from Ordering. */
  public static final String ORDER_LIFECYCLE = "allocation-ordering-events";

  /** Receives physical availability facts from Inventory. */
  public static final String INVENTORY_AVAILABILITY = "allocation-inventory-events";

  private AllocationEventSubscriptions() {
  }
}
