package com.flowzati.archone.ordering.entrypoint.rest;

/** 下單命令的 JSON request body。數量與 SKU 的規則由 Order aggregate 驗證，不在此重複。 */
public record PlaceOrderRequest(String sku, Integer quantity) {
}
