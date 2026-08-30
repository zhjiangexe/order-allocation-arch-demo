package com.flowzati.archone.inventory.movement.application.view;

import com.flowzati.archone.inventory.movement.domain.policy.MovementAssignmentPolicy;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationDirection;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationState;
import java.time.Instant;
import java.util.UUID;

/** The warehouse work group; locations are movement endpoints, not source-document identity. */
public record StockOperationHeaderView(
        UUID stockOperationId,
        UUID stockOperationTypeId,
        StockOperationDirection direction,
        UUID ownerId,
        UUID fromLocationId,
        UUID toLocationId,
        MovementAssignmentPolicy assignmentPolicy,
        Instant enqueuedAt,
        Instant dispatchBy,
        Integer releasePriority,
        StockOperationState state) {}
