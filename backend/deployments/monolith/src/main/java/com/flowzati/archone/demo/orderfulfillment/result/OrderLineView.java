package com.flowzati.archone.demo.orderfulfillment.result;

import java.util.UUID;

/** 履約查詢中的 immutable Order line。 */
public record OrderLineView(UUID orderLineId, int lineNo, String skuCode, int quantity) {}
