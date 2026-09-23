package com.flowzati.archone.inventory.api.operation;

import java.util.UUID;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;

public interface InventoryOperationQueryApi {

    @GetExchange("/internal/inventory/stock-operations/primary-order")
    InventoryOperationView findPrimaryOrder(@RequestParam("orderId") UUID orderId);
}
