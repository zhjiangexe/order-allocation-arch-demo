package com.flowzati.archone.inventory.allocation.infrastructure.persistence.jdbc.store;

import com.flowzati.archone.inventory.allocation.application.state.AssignmentQueueKey;
import com.flowzati.archone.inventory.allocation.application.state.StockOperationPredecessor;
import com.flowzati.archone.inventory.allocation.application.store.StockOperationAssignmentCandidateStore;
import com.flowzati.archone.inventory.allocation.domain.policy.AllocationSequencePolicy;
import com.flowzati.archone.inventory.allocation.domain.valueobject.StockOperationDemand;
import com.flowzati.archone.inventory.allocation.infrastructure.persistence.jdbc.model.AllocationSequenceSql;
import com.flowzati.archone.inventory.allocation.infrastructure.persistence.jdbc.model.StockOperationAssignmentCandidateRows;
import java.time.Instant;
import java.util.LinkedHashSet;
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
    public Optional<StockOperationDemand> findDemand(UUID stockOperationId) {
        if (stockOperationId == null) {
            throw new IllegalArgumentException("Pending operation ID is required");
        }
        // 一次投影完整 CONFIRMED operation demand，避免 Planner 操作 persistence aggregate。
        StockOperationAssignmentCandidateRows rows = new StockOperationAssignmentCandidateRows();
        String sql = """
                -- 讀取指定待配需求的完整明細，供 Planner 計算整張需求。
                SELECT operation.id AS operation_id,
                       operation.version AS operation_version,
                       operation.owner_id,
                       operation.from_location_id,
                       operation.policy_code AS assignment_policy,
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
                """;
        jdbcClient.sql(sql).param(stockOperationId).query(rows::add);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(rows.demand());
    }

    @Override
    public Optional<StockOperationDemand> findNext(AssignmentQueueKey queueKey, AllocationSequencePolicy policy) {
        if (queueKey == null) {
            throw new IllegalArgumentException("Pending operation queue key is required");
        }
        java.util.Objects.requireNonNull(policy, "policy is required");
        StockOperationAssignmentCandidateRows rows = new StockOperationAssignmentCandidateRows();
        String sql = """
                -- 先依貨主策略選一張 Operation；LIMIT 不可套在外層 Move 明細上。
                WITH selected_operation AS (
                    SELECT operation.id
                      FROM stock_operations operation
                     WHERE operation.state = 'CONFIRMED'
                       AND operation.direction = 'OUTBOUND'
                       AND operation.owner_id = ?
                       AND operation.from_location_id = ?
                       -- queue 的 SKU 只用來選單，不限制後續取回的需求明細。
                       AND EXISTS (
                             SELECT 1
                               FROM stock_moves move
                              WHERE move.stock_operation_id = operation.id
                                AND move.state = 'CONFIRMED'
                                AND move.sku_code = ?
                           )
                     ORDER BY %s
                     LIMIT 1
                )
                -- 取回選中需求的全部待配 Move，包含其他 SKU，避免只配到部分需求。
                SELECT operation.id AS operation_id,
                       operation.version AS operation_version,
                       operation.owner_id,
                       operation.from_location_id,
                       operation.policy_code AS assignment_policy,
                       move.id AS move_id,
                       move.version AS move_version,
                       move.source_line_id,
                       move.line_sequence,
                       move.sku_code,
                       move.demand_quantity
                  FROM selected_operation selected
                  JOIN stock_operations operation ON operation.id = selected.id
                  JOIN stock_moves move ON move.stock_operation_id = operation.id
                 WHERE operation.state = 'CONFIRMED'
                   AND operation.direction <> 'INBOUND'
                   AND operation.source_type IS NOT NULL
                   AND move.state = 'CONFIRMED'
                 ORDER BY move.line_sequence, move.id
                """.formatted(AllocationSequenceSql.columns(policy, "operation"));
        jdbcClient
                .sql(sql)
                .param(queueKey.ownerId())
                .param(queueKey.fromLocationId())
                .param(queueKey.skuCode())
                .query(rows::add);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(rows.demand());
    }

    @Override
    public Optional<StockOperationPredecessor> findPredecessor(
            StockOperationDemand demand, AllocationSequencePolicy policy) {
        String sql = """
                -- 同貨主、來源位置且共享 SKU 的需求，才會互相阻擋。
                SELECT predecessor.id, predecessor.enqueued_at
                  FROM stock_operations predecessor
                  JOIN stock_operations candidate ON candidate.id = ?
                 WHERE predecessor.state = 'CONFIRMED'
                   AND predecessor.direction = 'OUTBOUND'
                   AND predecessor.owner_id = ?
                   AND predecessor.from_location_id = ?
                   -- 依傳入策略逐欄比較順位，與選取候選時使用相同排序。
                   AND (%s) < (%s)
                   AND EXISTS (
                         SELECT 1
                           FROM stock_moves predecessor_move
                           JOIN stock_moves candidate_move
                             ON candidate_move.stock_operation_id = ?
                            AND candidate_move.sku_code = predecessor_move.sku_code
                          WHERE predecessor_move.stock_operation_id = predecessor.id
                            AND predecessor_move.state = 'CONFIRMED'
                       )
                 -- 不判斷前序需求是否有足夠庫存；只取順位最高的一張。
                 ORDER BY %s
                 LIMIT 1
                """.formatted(
                        AllocationSequenceSql.columns(policy, "predecessor"),
                        AllocationSequenceSql.columns(policy, "candidate"),
                        AllocationSequenceSql.columns(policy, "predecessor"));
        return jdbcClient
                .sql(sql)
                .param(demand.stockOperationId())
                .param(demand.ownerId())
                .param(demand.fromLocationId())
                .param(demand.stockOperationId())
                .query((row, ignored) -> new PredecessorRow(
                        row.getObject("id", UUID.class),
                        row.getTimestamp("enqueued_at").toInstant()))
                .optional()
                .map(predecessor -> new StockOperationPredecessor(
                        predecessor.id(),
                        predecessor.enqueuedAt(),
                        findSharedSkuCodes(demand.stockOperationId(), predecessor.id())));
    }

    private Set<String> findSharedSkuCodes(UUID candidateId, UUID predecessorId) {
        return new LinkedHashSet<>(jdbcClient
                .sql("""
                -- 前序需求已找到後，補查共享 SKU，供阻擋原因的日誌使用。
                SELECT DISTINCT candidate_move.sku_code
                  FROM stock_moves candidate_move
                  JOIN stock_moves predecessor_move ON predecessor_move.sku_code = candidate_move.sku_code
                 WHERE candidate_move.stock_operation_id = ?
                   AND predecessor_move.stock_operation_id = ?
                 ORDER BY candidate_move.sku_code
                """)
                .param(candidateId)
                .param(predecessorId)
                .query(String.class)
                .list());
    }

    private record PredecessorRow(UUID id, Instant enqueuedAt) {}
}
