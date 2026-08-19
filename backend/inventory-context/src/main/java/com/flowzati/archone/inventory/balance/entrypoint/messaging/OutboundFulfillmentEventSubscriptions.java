package com.flowzati.archone.inventory.balance.entrypoint.messaging;

/** Inventory outbound event consumers 的 stable Inbox subscriber identities。 */
public final class OutboundFulfillmentEventSubscriptions {

    public static final String SHIPMENT_HANDOVER = "inventory-shipment-handover";

    private OutboundFulfillmentEventSubscriptions() {}
}
