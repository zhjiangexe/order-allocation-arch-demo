package com.flowzati.archone.contracts.inventory.v2;

/** Canonical and compatibility aggregate types for Inventory stock-operation history. */
public final class InventoryAggregateTypes {

    public static final String STOCK_OPERATION = "StockOperation";
    public static final String LEGACY_STOCK_PICKING =
            com.flowzati.archone.contracts.inventory.v1.InventoryAggregateTypes.STOCK_PICKING;

    private InventoryAggregateTypes() {}

    public static boolean representsStockOperation(String aggregateType) {
        return STOCK_OPERATION.equals(aggregateType) || LEGACY_STOCK_PICKING.equals(aggregateType);
    }
}
