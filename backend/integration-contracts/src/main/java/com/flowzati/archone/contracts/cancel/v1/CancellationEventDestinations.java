package com.flowzati.archone.contracts.cancel.v1;

/** Stable logical destinations for cancellation commands and outcomes. */
public final class CancellationEventDestinations {

    public static final String CANCELLATION_REQUESTS = "fulfillment.cancellation-requests";
    public static final String SHIPMENT_EVENTS = "wms.shipment-events";
    public static final String ORDERING_CANCELLATION_REQUESTS = "fulfillment.ordering-cancellation-requests";
    public static final String ORDERING_CANCELLATION_RESULTS = "ordering.cancellation-results";

    private CancellationEventDestinations() {}
}
