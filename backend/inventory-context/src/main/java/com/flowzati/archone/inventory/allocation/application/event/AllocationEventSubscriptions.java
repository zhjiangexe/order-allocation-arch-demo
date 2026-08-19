package com.flowzati.archone.inventory.allocation.application.event;

/** Stable identities of event subscriptions owned by the Allocation bounded context. */
public final class AllocationEventSubscriptions {

    /** OrderPlaced driver；事件版與 Temporal 版共用此 identity，切換時不得重播歷史訂單。 */
    public static final String ORDER_PLACEMENT_DRIVER = "allocation-ordering-events";

    /** OrderCancelled compensation；兩種 orchestration mode 都必須處理。 */
    public static final String ORDER_CANCELLATIONS = "allocation-order-cancellations";

    /** Receives physical availability facts from Inventory. */
    public static final String INVENTORY_AVAILABILITY = "allocation-inventory-events";

    private AllocationEventSubscriptions() {}
}
