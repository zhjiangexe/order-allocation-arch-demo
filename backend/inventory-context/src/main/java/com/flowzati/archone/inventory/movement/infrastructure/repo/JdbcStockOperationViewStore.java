package com.flowzati.archone.inventory.movement.infrastructure.repo;

import com.flowzati.archone.inventory.movement.application.StockOperationView;
import com.flowzati.archone.inventory.movement.application.StockOperationView.Move;
import com.flowzati.archone.inventory.movement.application.StockOperationView.MoveLineView;
import com.flowzati.archone.inventory.movement.application.StockOperationView.Operation;
import com.flowzati.archone.inventory.movement.application.StockOperationView.SourceTrace;
import com.flowzati.archone.inventory.movement.application.repo.StockOperationViewStore;
import com.flowzati.archone.inventory.movement.domain.MoveState;
import com.flowzati.archone.inventory.movement.domain.MovementAssignmentPolicy;
import com.flowzati.archone.inventory.movement.domain.MovementSourceType;
import com.flowzati.archone.inventory.movement.domain.StockOperationDirection;
import com.flowzati.archone.inventory.movement.domain.StockOperationSource;
import com.flowzati.archone.inventory.movement.domain.StockOperationState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** One-statement read adapter; result cardinality changes row count, never query count. */
@Repository
public class JdbcStockOperationViewStore implements StockOperationViewStore {

    private static final String PROJECTION = """
      SELECT operation.id AS operation_id,
             operation.stock_operation_type_id,
             operation.direction,
             operation.owner_id,
             operation.from_location_id,
             operation.to_location_id,
             operation.source_type,
             operation.source_id,
             operation.allocation_unit_key,
             operation.policy_code AS assignment_policy,
             operation.enqueued_at,
             operation.dispatch_by,
             operation.release_priority,
             operation.state AS operation_state,
             move.id AS move_id,
             move.source_line_id,
             move.line_sequence,
             move.sku_code AS move_sku_code,
             move.demand_quantity,
             move.state AS move_state,
             move.created_at,
             move.assigned_at,
             line.id AS move_line_id,
             line.stock_pool_id AS stock_quant_id,
             line.quantity AS reserved_quantity,
             quant.location_id AS quant_location_id,
             quant.sku_code AS quant_sku_code,
             quant.in_date,
             quant.expiry_date
        FROM selected_operation operation
        JOIN stock_moves move ON move.stock_operation_id = operation.id
        LEFT JOIN stock_move_lines line ON line.move_id = move.id
        LEFT JOIN stock_pools quant ON quant.id = line.stock_pool_id
       ORDER BY operation.enqueued_at, operation.id, move.line_sequence, move.id, line.stock_pool_id, line.id
      """;

    private final JdbcClient jdbcClient;

    public JdbcStockOperationViewStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<StockOperationView> findConfirmedOutbound(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("Confirmed operation query limit must be positive");
        }
        String sql = """
          WITH selected_operation AS (
            SELECT *
              FROM stock_operations
             WHERE state = 'CONFIRMED'
               AND direction = 'OUTBOUND'
             ORDER BY enqueued_at, id
             LIMIT ?
          )
          """ + PROJECTION;
        return query(sql, limit);
    }

    @Override
    public Optional<StockOperationView> findBySource(StockOperationSource source) {
        if (source == null) {
            throw new IllegalArgumentException("Stock operation source is required");
        }
        String sql = """
          WITH selected_operation AS (
            SELECT *
              FROM stock_operations
             WHERE source_type = ?
               AND source_id = ?
               AND allocation_unit_key = ?
          )
          """ + PROJECTION;
        List<StockOperationView> views =
                query(sql, source.sourceType().name(), source.sourceId(), source.allocationUnitKey());
        if (views.size() > 1) {
            throw new IllegalStateException("Source identity resolved to more than one stock operation");
        }
        return views.stream().findFirst();
    }

    private List<StockOperationView> query(String sql, Object... arguments) {
        ProjectionAccumulator accumulator = new ProjectionAccumulator();
        JdbcClient.StatementSpec statement = jdbcClient.sql(sql);
        for (Object argument : arguments) {
            statement = statement.param(argument);
        }
        statement.query(accumulator::add);
        return accumulator.views();
    }

    private static final class ProjectionAccumulator {

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
                move.moveLines.add(new MoveLineView(
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
                SourceTrace source = sourceType == null
                        ? null
                        : new SourceTrace(
                                MovementSourceType.valueOf(sourceType),
                                row.getString("source_id"),
                                row.getString("allocation_unit_key"));
                Operation value = new Operation(
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
    }

    private static final class OperationRows {

        private final SourceTrace source;
        private final Operation operation;
        private final Map<UUID, MoveRows> moves = new LinkedHashMap<>();

        private OperationRows(SourceTrace source, Operation operation) {
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
        private final List<MoveLineView> moveLines = new ArrayList<>();

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

        private Move view() {
            return new Move(id, sourceLineId, lineSequence, skuCode, quantity, state, createdAt, assignedAt, moveLines);
        }
    }

    private static final class ProjectionReadException extends RuntimeException {

        private ProjectionReadException(SQLException cause) {
            super(cause);
        }
    }
}
