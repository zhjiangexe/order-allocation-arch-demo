package com.flowzati.archone.ordering.application.event;

/** Stable identities of event subscriptions owned by the Ordering bounded context. */
public final class OrderingEventSubscriptions {

  /** Records allocation outcomes emitted by Promising. */
  public static final String ALLOCATION_RESULTS = "ordering-allocation-events";

  /** Kafka offset scope; deliberately modelled separately from the Inbox subscriber identity. */
  public static final String ALLOCATION_RESULTS_CONSUMER_GROUP =
      "ordering-allocation-events";

  private OrderingEventSubscriptions() {
  }
}
