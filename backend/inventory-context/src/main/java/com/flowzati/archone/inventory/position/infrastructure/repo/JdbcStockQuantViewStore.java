package com.flowzati.archone.inventory.position.infrastructure.repo;

import com.flowzati.archone.inventory.position.application.StockQuantView;
import com.flowzati.archone.inventory.position.application.store.StockQuantViewStore;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Operator-facing balance projection, isolated from quant command persistence and planner eligibility rules. */
@Repository
public class JdbcStockQuantViewStore implements StockQuantViewStore {

    private static final String SQL = """
      SELECT id, sku_code, in_date, expiry_date, on_hand_quantity, reserved_quantity
        FROM stock_pools
       WHERE owner_id = ?
         AND location_id = ?
       ORDER BY sku_code, expiry_date, in_date, id
      """;

    private final JdbcClient jdbcClient;

    public JdbcStockQuantViewStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<StockQuantView> findBatchesInLocation(UUID ownerId, UUID locationId) {
        return List.copyOf(jdbcClient
                .sql(SQL)
                .param(ownerId)
                .param(locationId)
                .query((row, rowNumber) -> new StockQuantView(
                        row.getObject("id", UUID.class),
                        row.getString("sku_code"),
                        row.getObject("in_date", java.time.LocalDate.class),
                        row.getObject("expiry_date", java.time.LocalDate.class),
                        row.getInt("on_hand_quantity"),
                        row.getInt("reserved_quantity")))
                .list());
    }
}
