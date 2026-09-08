package com.flowzati.archone.inventory.allocation.infrastructure.persistence.jdbc.store;

import com.flowzati.archone.inventory.allocation.application.state.AssignmentQueueKey;
import com.flowzati.archone.inventory.allocation.application.store.StockOperationAssignmentBacklogStore;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Focused bounded anti-entropy discovery isolated from aggregate persistence. */
@Repository
public class JdbcStockOperationAssignmentBacklogStoreAdapter implements StockOperationAssignmentBacklogStore {

    private final JdbcClient jdbcClient;

    public JdbcStockOperationAssignmentBacklogStoreAdapter(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<AssignmentQueueKey> findQueueKeysWithAvailableStock(
            LocalDate today, int limit, AssignmentQueueKey after) {
        if (today == null || limit <= 0) {
            throw new IllegalArgumentException("Pending operation queue date and positive limit are required");
        }
        String cursorCondition = after == null
                ? ""
                : "AND (operation.owner_id, operation.from_location_id, move.sku_code) > (:afterOwner, :afterLocation, :afterSku)";
        var query = jdbcClient
                .sql("""
                SELECT operation.owner_id,
                       operation.from_location_id,
                       move.sku_code
                  FROM stock_operations operation
                  JOIN stock_moves move ON move.stock_operation_id = operation.id
                 WHERE operation.state = 'CONFIRMED'
                   AND operation.direction = 'OUTBOUND'
                   AND move.state = 'CONFIRMED'
                   AND EXISTS (
                         SELECT 1
                           FROM stock_pools quant
                          WHERE quant.owner_id = operation.owner_id
                            AND quant.location_id = operation.from_location_id
                            AND quant.sku_code = move.sku_code
                            AND quant.expiry_date >= :today
                            AND quant.on_hand_quantity > quant.reserved_quantity
                       )
                 %s
                 GROUP BY operation.owner_id, operation.from_location_id, move.sku_code
                 ORDER BY operation.owner_id, operation.from_location_id, move.sku_code
                 LIMIT :limit
                """.formatted(cursorCondition))
                .param("today", today)
                .param("limit", limit);
        if (after != null) {
            query.param("afterOwner", after.ownerId())
                    .param("afterLocation", after.fromLocationId())
                    .param("afterSku", after.skuCode());
        }
        return query.query((row, ignored) -> new AssignmentQueueKey(
                        row.getObject("owner_id", java.util.UUID.class),
                        row.getObject("from_location_id", java.util.UUID.class),
                        row.getString("sku_code")))
                .list();
    }
}
