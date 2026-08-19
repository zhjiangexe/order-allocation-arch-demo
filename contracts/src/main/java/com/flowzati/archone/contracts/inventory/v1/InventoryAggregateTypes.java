package com.flowzati.archone.contracts.inventory.v1;

/** Inventory events 寫入 Outbox 時使用的 stable aggregate type。 */
public final class InventoryAggregateTypes {

    public static final String STOCK_POOL = "StockPool";

    private InventoryAggregateTypes() {}
}
