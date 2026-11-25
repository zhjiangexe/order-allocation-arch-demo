package com.flowzati.archone.inventory.movement.entrypoint.messaging;

/**
 * Movement 模組所有 inbound consumer 的 Inbox subscriber identity。
 *
 * <p>值已寫入 Inbox 去重紀錄，**永遠不可修改**；改值會讓已處理的事件被當成新事件重送。常數名稱可以調整。
 */
public final class MovementEventSubscriptions {

    public static final String ORDER_CANCELLATIONS = "allocation-order-cancellations";

    public static final String SHIPMENT_HANDOVER = "inventory-shipment-handover";

    private MovementEventSubscriptions() {}
}
