package com.flowzati.archone.demo.fulfillment;

import java.util.UUID;

/** 履約查詢中的 immutable Order line。 */
public record FulfillmentOrderLineView(UUID orderLineId, int lineNo, String skuCode, int quantity) {}
