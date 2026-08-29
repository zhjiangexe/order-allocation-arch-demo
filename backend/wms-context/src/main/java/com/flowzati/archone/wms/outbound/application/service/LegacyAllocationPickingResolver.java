package com.flowzati.archone.wms.outbound.application.service;

import java.util.List;
import java.util.UUID;

/** Compatibility port that translates a retained V1 allocation fact to canonical Inventory identity. */
@FunctionalInterface
public interface LegacyAllocationPickingResolver {

    UUID resolve(UUID legacyAllocationId, List<UUID> moveIds);
}
