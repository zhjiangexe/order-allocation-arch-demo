package com.flowzati.archone.inventory.allocation.infrastructure.persistence.jdbc.store;

import com.flowzati.archone.inventory.allocation.application.state.AssignmentQueueKey;
import com.flowzati.archone.inventory.allocation.application.state.StockOperationAssignmentCandidate;
import com.flowzati.archone.inventory.allocation.application.state.StockOperationPredecessor;
import com.flowzati.archone.inventory.allocation.application.store.StockOperationAssignmentCandidateStore;
import com.flowzati.archone.inventory.allocation.domain.valueobject.StockOperationDemand;
import com.flowzati.archone.inventory.allocation.infrastructure.persistence.jdbc.model.StockOperationAssignmentCandidateRows;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Focused JDBC Store returning immutable assignment-planning input. */
@Repository
public class JdbcStockOperationAssignmentCandidateStoreAdapter implements StockOperationAssignmentCandidateStore {

    private final JdbcClient jdbcClient;

    public JdbcStockOperationAssignmentCandidateStoreAdapter(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public StockOperationAssignmentCandidate findByOperationId(UUID stockOperationId) {
        if (stockOperationId == null) {
            throw new IllegalArgumentException("Pending operation ID is required");
        }
        // 一次投影完整 CONFIRMED operation demand，避免 Planner 操作 persistence aggregate。
        StockOperationAssignmentCandidateRows rows = new StockOperationAssignmentCandidateRows();
        jdbcClient.sql("""
                SELECT operation.id AS operation_id,
                       operation.version AS operation_version,
                       operation.owner_id,
                       operation.from_location_id,
                       operation.policy_code AS assignment_policy,
                       operation.enqueued_at,
                       move.id AS move_id,
                       move.version AS move_version,
                       move.source_line_id,
                       move.line_sequence,
                       move.sku_code,
                       move.demand_quantity
                  FROM stock_operations operation
                  JOIN stock_moves move ON move.stock_operation_id = operation.id
                 WHERE operation.id = ?
                   AND operation.state = 'CONFIRMED'
                   AND operation.direction <> 'INBOUND'
                   AND operation.source_type IS NOT NULL
                   AND move.state = 'CONFIRMED'
                 ORDER BY move.line_sequence, move.id
                """).param(stockOperationId).query(rows::add);
        StockOperationDemand demand = rows.demand(stockOperationId);
        // predecessor 與 demand 一起回傳，selection 規則不洩漏到 Use Case。
        return new StockOperationAssignmentCandidate(demand, findPredecessor(demand, rows.enqueuedAt()));
    }

    @Override
    public Optional<StockOperationAssignmentCandidate> findNext(AssignmentQueueKey queueKey) {
        if (queueKey == null) {
            throw new IllegalArgumentException("Pending operation queue key is required");
        }
        return jdbcClient
                .sql("""
                    SELECT operation.id
                      FROM stock_operations operation
                     WHERE operation.state = 'CONFIRMED'
                       AND operation.direction = 'OUTBOUND'
                       AND operation.owner_id = ?
                       AND operation.from_location_id = ?
                       AND EXISTS (
                             SELECT 1
                               FROM stock_moves move
                              WHERE move.stock_operation_id = operation.id
                                AND move.state = 'CONFIRMED'
                                AND move.sku_code = ?
                           )
                     ORDER BY operation.enqueued_at, operation.id
                     LIMIT 1
                    """)
                .param(queueKey.ownerId())
                .param(queueKey.fromLocationId())
                .param(queueKey.skuCode())
                .query(UUID.class)
                .optional()
                .map(this::findByOperationId);
    }

    private Optional<StockOperationPredecessor> findPredecessor(StockOperationDemand demand, Instant enqueuedAt) {
        // 只阻擋同貨主、同來源庫位且共享 SKU 的較早 Operation。
        List<PredecessorRow> predecessors = jdbcClient
                .sql("""
                SELECT predecessor.id, predecessor.enqueued_at
                  FROM stock_operations predecessor
                 WHERE predecessor.state = 'CONFIRMED'
                   AND predecessor.direction = 'OUTBOUND'
                   AND predecessor.owner_id = ?
                   AND predecessor.from_location_id = ?
                   AND (predecessor.enqueued_at, predecessor.id) < (?, ?)
                   AND EXISTS (
                         SELECT 1
                           FROM stock_moves predecessor_move
                           JOIN stock_moves candidate_move
                             ON candidate_move.stock_operation_id = ?
                            AND candidate_move.sku_code = predecessor_move.sku_code
                          WHERE predecessor_move.stock_operation_id = predecessor.id
                            AND predecessor_move.state = 'CONFIRMED'
                       )
                 ORDER BY predecessor.enqueued_at, predecessor.id
                 LIMIT 1
                """)
                .param(demand.ownerId())
                .param(demand.fromLocationId())
                .param(Timestamp.from(enqueuedAt))
                .param(demand.stockOperationId())
                .param(demand.stockOperationId())
                .query((row, ignored) -> new PredecessorRow(
                        row.getObject("id", UUID.class),
                        row.getTimestamp("enqueued_at").toInstant()))
                .list();
        if (predecessors.isEmpty()) {
            return Optional.empty();
        }
        PredecessorRow predecessor = predecessors.getFirst();
        Set<String> sharedSkus = new LinkedHashSet<>(jdbcClient
                .sql("""
                SELECT DISTINCT candidate_move.sku_code
                  FROM stock_moves candidate_move
                  JOIN stock_moves predecessor_move ON predecessor_move.sku_code = candidate_move.sku_code
                 WHERE candidate_move.stock_operation_id = ?
                   AND predecessor_move.stock_operation_id = ?
                 ORDER BY candidate_move.sku_code
                """)
                .param(demand.stockOperationId())
                .param(predecessor.id())
                .query(String.class)
                .list());
        return Optional.of(new StockOperationPredecessor(predecessor.id(), predecessor.enqueuedAt(), sharedSkus));
    }

    private record PredecessorRow(UUID id, Instant enqueuedAt) {}
}
