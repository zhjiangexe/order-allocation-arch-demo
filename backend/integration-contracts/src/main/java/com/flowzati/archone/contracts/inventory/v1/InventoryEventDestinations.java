package com.flowzati.archone.contracts.inventory.v1;

/** Inventory V1 integration events 的 stable logical destinations。 */
public final class InventoryEventDestinations {

    public static final String STOCK_EVENTS = "inventory.stock-events";
    public static final String STOCK_OPERATION_EVENTS = "inventory.stock-operation-events";

    private InventoryEventDestinations() {}
}
