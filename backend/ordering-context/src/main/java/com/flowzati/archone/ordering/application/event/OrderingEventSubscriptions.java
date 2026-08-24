package com.flowzati.archone.ordering.application.event;

/** Stable identities of event subscriptions owned by the Ordering bounded context. */
public final class OrderingEventSubscriptions {

    /** Records allocation outcomes emitted by Allocation. */
    public static final String ALLOCATION_RESULTS = "ordering-allocation-events";

    /** Records physical outbound completion emitted by Inventory. */
    public static final String FULFILLMENT_COMPLETION = "ordering-fulfillment-completion";

    /** Cancels Orders only after WMS reports a physically safe Shipment terminal state. */
    public static final String SHIPMENT_CANCELLATIONS = "ordering-shipment-cancellations";

    private OrderingEventSubscriptions() {}
}
