package com.flowzati.archone.inventory.allocation.application.query;

import java.util.Map;
import java.util.UUID;

/** Pending demand 的即時供需解釋；package-private，避免成為外部 API。 */
record AllocationSupplyExplanation(
        AllocationWaitingReason reason,
        UUID blockedByDemandId,
        Map<String, Integer> availableQuantities,
        Map<String, Integer> missingQuantities) {

    static AllocationSupplyExplanation notPending() {
        return new AllocationSupplyExplanation(null, null, Map.of(), Map.of());
    }
}
