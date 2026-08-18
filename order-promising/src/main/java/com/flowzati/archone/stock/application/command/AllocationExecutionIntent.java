package com.flowzati.archone.stock.application.command;

import com.flowzati.archone.catalog.domain.model.PickingDirection;
import java.util.UUID;

/** Immutable warehouse execution intent captured when a source demand is accepted. */
public record AllocationExecutionIntent(
    UUID pickingTypeId,
    PickingDirection direction,
    UUID fromLocationId,
    UUID toLocationId,
    boolean createPicking,
    UUID legacyOrderId
) {

  public AllocationExecutionIntent {
    if (direction == null || direction == PickingDirection.INBOUND) {
      throw new IllegalArgumentException("Allocation demand execution must consume stock");
    }
    if (fromLocationId == null || toLocationId == null) {
      throw new IllegalArgumentException("Allocation execution requires source and destination locations");
    }
    if (createPicking && pickingTypeId == null) {
      throw new IllegalArgumentException("Picking creation requires a picking type");
    }
    if (!createPicking && (pickingTypeId != null || legacyOrderId != null)) {
      throw new IllegalArgumentException("Execution without a picking cannot carry picking references");
    }
  }
}
