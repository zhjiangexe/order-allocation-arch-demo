package com.flowzati.archone.wms.shipment.entrypoint.messaging;

/** Stable Inbox subscriber identities owned by the WMS deployable. */
public final class WmsEventSubscriptions {

    public static final String FULFILLMENT_HANDOFF = "wms-fulfillment-handoff";
    public static final String ORDER_CANCELLATION_REQUESTS = "wms-order-cancellation-requests";

    private WmsEventSubscriptions() {}
}
