package com.flowzati.archone.inventory.allocation.entrypoint;

/** Stable Inbox identity for Reservation intake. */
public final class ReservationIntakeEventSubscriptions {

    /** OrderPlaced driver；事件版與 Temporal 版共用此 identity，切換時不得重播歷史訂單。 */
    public static final String ORDER_PLACEMENT_DRIVER = "allocation-ordering-events";

    private ReservationIntakeEventSubscriptions() {}
}
