package com.flowzati.archone.inventory.allocation.application.store;

import com.flowzati.archone.inventory.movement.application.invocation.RegisterStockOperationCommand;
import java.util.Optional;
import java.util.UUID;

/** Reads the source-owned order projection and returns a normalized Inventory movement command. */
@FunctionalInterface
public interface OrderStockMovementStore {

    Optional<RegisterStockOperationCommand> find(UUID orderId);
}
