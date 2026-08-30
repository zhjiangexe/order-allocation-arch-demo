package com.flowzati.archone.inventory.allocation.application.projection;

import com.flowzati.archone.inventory.allocation.domain.valueobject.StockOperationDemand;
import java.util.Optional;

/** Immutable assignment input selected for one confirmed stock operation. */
public record StockOperationAssignmentCandidate(
        StockOperationDemand demand, Optional<StockOperationPredecessor> predecessor) {

    public StockOperationAssignmentCandidate {
        if (demand == null || predecessor == null) {
            throw new IllegalArgumentException("Assignment candidate must be complete");
        }
    }
}
