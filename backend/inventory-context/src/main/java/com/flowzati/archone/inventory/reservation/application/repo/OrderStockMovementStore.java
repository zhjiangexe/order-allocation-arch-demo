package com.flowzati.archone.inventory.reservation.application.repo;

import com.flowzati.archone.inventory.movement.application.command.RegisterStockOperationCommand;
import java.util.Optional;
import java.util.UUID;

/** Reads the source-owned order projection and returns a normalized Inventory movement command. */
@FunctionalInterface
public interface OrderStockMovementStore {

    Optional<RegisterStockOperationCommand> find(UUID orderId);
}
