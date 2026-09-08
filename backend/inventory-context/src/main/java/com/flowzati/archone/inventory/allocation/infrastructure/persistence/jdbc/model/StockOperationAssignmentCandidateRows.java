package com.flowzati.archone.inventory.allocation.infrastructure.persistence.jdbc.model;

import com.flowzati.archone.inventory.allocation.domain.valueobject.StockMoveDemand;
import com.flowzati.archone.inventory.allocation.domain.valueobject.StockOperationDemand;
import com.flowzati.archone.inventory.movement.domain.policy.MovementAssignmentPolicy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/** Folds the joined candidate query rows into one immutable Stock Operation demand. */
public final class StockOperationAssignmentCandidateRows {

    private UUID operationId;
    private Long operationVersion;
    private UUID ownerId;
    private UUID fromLocationId;
    private MovementAssignmentPolicy policy;
    private final List<StockMoveDemand> moves = new ArrayList<>();

    public void add(ResultSet row) throws SQLException {
        UUID rowOperationId = row.getObject("operation_id", UUID.class);
        if (operationId == null) {
            operationId = rowOperationId;
            operationVersion = row.getLong("operation_version");
            ownerId = row.getObject("owner_id", UUID.class);
            fromLocationId = row.getObject("from_location_id", UUID.class);
            policy = MovementAssignmentPolicy.valueOf(row.getString("assignment_policy"));
        } else if (!operationId.equals(rowOperationId)) {
            throw new IllegalStateException("Candidate query returned more than one operation");
        }
        moves.add(new StockMoveDemand(
                row.getObject("move_id", UUID.class),
                row.getLong("move_version"),
                row.getString("source_line_id"),
                row.getInt("line_sequence"),
                row.getString("sku_code"),
                row.getInt("demand_quantity")));
    }

    public boolean isEmpty() {
        return operationId == null;
    }

    public StockOperationDemand demand() {
        if (operationId == null || moves.isEmpty()) {
            throw new NoSuchElementException("Confirmed stock operation not found: " + operationId);
        }
        return new StockOperationDemand(operationId, operationVersion, ownerId, fromLocationId, policy, moves);
    }
}
