package com.flowzati.archone.inventory.allocation.application;

import com.flowzati.archone.inventory.allocation.domain.StockOperationDemand;
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
