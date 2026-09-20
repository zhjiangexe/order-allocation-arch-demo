package com.flowzati.archone.inventory.movement.infrastructure.persistence.jdbc.store;

import com.flowzati.archone.inventory.movement.application.result.StockMoveLineView;
import com.flowzati.archone.inventory.movement.application.result.StockMoveView;
import com.flowzati.archone.inventory.movement.application.result.StockOperationHeaderView;
import com.flowzati.archone.inventory.movement.application.result.StockOperationSourceView;
import com.flowzati.archone.inventory.movement.application.result.StockOperationView;
import com.flowzati.archone.inventory.movement.domain.policy.MovementAssignmentPolicy;
import com.flowzati.archone.inventory.movement.domain.valueobject.MoveState;
import com.flowzati.archone.inventory.movement.domain.valueobject.MovementSourceType;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationDirection;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Folds the joined JDBC rows of one projection query into immutable Stock Operation views. */
final class StockOperationViewRowAccumulator {

    private final Map<UUID, OperationRows> operations = new LinkedHashMap<>();

    void add(ResultSet row) throws SQLException {
        UUID operationId = row.getObject("operation_id", UUID.class);
        OperationRows operation = operations.computeIfAbsent(operationId, ignored -> operation(row));
        UUID moveId = row.getObject("move_id", UUID.class);
        MoveRows move = operation.moves.computeIfAbsent(moveId, ignored -> move(row));
        UUID moveLineId = row.getObject("move_line_id", UUID.class);
        if (moveLineId != null) {
            UUID quantId = row.getObject("stock_quant_id", UUID.class);
            if (quantId == null) {
                throw new IllegalStateException("Stock move line references a missing stock quant");
            }
            move.moveLines.add(new StockMoveLineView(
                    quantId,
                    row.getObject("quant_location_id", UUID.class),
                    row.getString("quant_sku_code"),
                    row.getObject("in_date", java.time.LocalDate.class),
                    row.getObject("expiry_date", java.time.LocalDate.class),
                    row.getInt("reserved_quantity")));
        }
    }

    List<StockOperationView> views() {
        return operations.values().stream().map(OperationRows::view).toList();
    }

    private static OperationRows operation(ResultSet row) {
        try {
            String sourceType = row.getString("source_type");
            StockOperationSourceView source = sourceType == null
                    ? null
                    : new StockOperationSourceView(
                            MovementSourceType.valueOf(sourceType),
                            row.getString("source_id"),
                            row.getString("allocation_unit_key"));
            StockOperationHeaderView value = new StockOperationHeaderView(
                    row.getObject("operation_id", UUID.class),
                    row.getObject("stock_operation_type_id", UUID.class),
                    StockOperationDirection.valueOf(row.getString("direction")),
                    row.getObject("owner_id", UUID.class),
                    row.getObject("from_location_id", UUID.class),
                    row.getObject("to_location_id", UUID.class),
                    MovementAssignmentPolicy.valueOf(row.getString("assignment_policy")),
                    instant(row, "enqueued_at"),
                    instant(row, "dispatch_by"),
                    (Integer) row.getObject("release_priority"),
                    StockOperationState.valueOf(row.getString("operation_state")));
            return new OperationRows(source, value);
        } catch (SQLException exception) {
            throw new ProjectionReadException(exception);
        }
    }

    private static MoveRows move(ResultSet row) {
        try {
            return new MoveRows(
                    row.getObject("move_id", UUID.class),
                    row.getString("source_line_id"),
                    (Integer) row.getObject("line_sequence"),
                    row.getString("move_sku_code"),
                    row.getInt("demand_quantity"),
                    MoveState.valueOf(row.getString("move_state")),
                    instant(row, "created_at"),
                    instant(row, "assigned_at"));
        } catch (SQLException exception) {
            throw new ProjectionReadException(exception);
        }
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static final class OperationRows {

        private final StockOperationSourceView source;
        private final StockOperationHeaderView operation;
        private final Map<UUID, MoveRows> moves = new LinkedHashMap<>();

        private OperationRows(StockOperationSourceView source, StockOperationHeaderView operation) {
            this.source = source;
            this.operation = operation;
        }

        private StockOperationView view() {
            return new StockOperationView(
                    source,
                    operation,
                    moves.values().stream().map(MoveRows::view).toList());
        }
    }

    private static final class MoveRows {

        private final UUID id;
        private final String sourceLineId;
        private final Integer lineSequence;
        private final String skuCode;
        private final int quantity;
        private final MoveState state;
        private final Instant createdAt;
        private final Instant assignedAt;
        private final List<StockMoveLineView> moveLines = new ArrayList<>();

        private MoveRows(
                UUID id,
                String sourceLineId,
                Integer lineSequence,
                String skuCode,
                int quantity,
                MoveState state,
                Instant createdAt,
                Instant assignedAt) {
            this.id = id;
            this.sourceLineId = sourceLineId;
            this.lineSequence = lineSequence;
            this.skuCode = skuCode;
            this.quantity = quantity;
            this.state = state;
            this.createdAt = createdAt;
            this.assignedAt = assignedAt;
        }

        private StockMoveView view() {
            return new StockMoveView(
                    id, sourceLineId, lineSequence, skuCode, quantity, state, createdAt, assignedAt, moveLines);
        }
    }

    private static final class ProjectionReadException extends RuntimeException {

        private ProjectionReadException(SQLException cause) {
            super(cause);
        }
    }
}
