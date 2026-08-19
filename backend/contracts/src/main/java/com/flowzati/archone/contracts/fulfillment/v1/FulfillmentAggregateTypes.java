package com.flowzati.archone.contracts.fulfillment.v1;

/** Fulfillment handoff 寫入 Outbox 時使用的 stable aggregate type。 */
public final class FulfillmentAggregateTypes {

    public static final String STOCK_PICKING = "StockPicking";
    public static final String WMS_SHIPMENT = "WmsShipment";

    private FulfillmentAggregateTypes() {}
}
