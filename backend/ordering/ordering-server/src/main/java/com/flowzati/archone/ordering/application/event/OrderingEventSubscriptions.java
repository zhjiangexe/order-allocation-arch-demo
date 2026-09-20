package com.flowzati.archone.ordering.application.event;

/** Stable identities of event subscriptions owned by the Ordering bounded context. */
public final class OrderingEventSubscriptions {

    /** Records allocation outcomes emitted by Allocation. */
    public static final String ALLOCATION_RESULTS = "ordering-allocation-events";

    /** Records physical outbound completion emitted by Inventory. */
    public static final String FULFILLMENT_COMPLETION = "ordering-fulfillment-completion";

    /** Receives cancellation commands after the process manager resolves WMS safety. */
    public static final String CANCELLATION_REQUESTS = "ordering-cancellation-requests";

    private OrderingEventSubscriptions() {}
}
