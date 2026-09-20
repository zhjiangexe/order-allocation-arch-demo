package com.flowzati.archone.inventory.allocation.entrypoint.messaging;

import java.util.Set;

/** Durable Inbox subscriber identities owned by Allocation. */
public final class AllocationSubscriberIds {

    public static final String ORDER_PLACEMENT = "allocation-ordering-events";
    public static final String INVENTORY_AVAILABILITY = "allocation-inventory-events";
    public static final Set<String> ALL = Set.of(ORDER_PLACEMENT, INVENTORY_AVAILABILITY);

    private AllocationSubscriberIds() {}
}
