package com.flowzati.archone.contracts.fulfillment.v1;

/** V1 fulfillment contracts 的 stable logical destinations。 */
public final class FulfillmentEventDestinations {

    public static final String FULFILLMENT_HANDOFFS = "promising.fulfillment-handoffs";
    public static final String SHIPMENT_EVENTS = "wms.shipment-events";

    private FulfillmentEventDestinations() {}
}
