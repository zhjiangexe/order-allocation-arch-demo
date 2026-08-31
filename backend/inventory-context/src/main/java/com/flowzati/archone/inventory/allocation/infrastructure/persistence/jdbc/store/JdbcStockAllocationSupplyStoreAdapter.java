package com.flowzati.archone.inventory.allocation.infrastructure.persistence.jdbc.store;

import com.flowzati.archone.inventory.allocation.application.store.StockAllocationSupplyStore;
import com.flowzati.archone.inventory.allocation.domain.valueobject.StockAllocationSupply;
import com.flowzati.archone.inventory.allocation.domain.valueobject.StockQuantSupply;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Focused multi-SKU FEFO supply Store, isolated from mutable quant persistence. */
@Repository
public class JdbcStockAllocationSupplyStoreAdapter implements StockAllocationSupplyStore {

    private static final String SQL = """
      SELECT id, owner_id, location_id, sku_code, in_date, expiry_date,
             on_hand_quantity, reserved_quantity
        FROM stock_pools
       WHERE owner_id = :ownerId
         AND location_id = :locationId
         AND sku_code IN (:skuCodes)
         AND expiry_date >= :today
         AND on_hand_quantity > reserved_quantity
       ORDER BY sku_code, expiry_date, in_date, id
      """;

    private final JdbcClient jdbcClient;

    public JdbcStockAllocationSupplyStoreAdapter(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public StockAllocationSupply findBySku(
            UUID ownerId, UUID locationId, Collection<String> skuCodes, LocalDate today) {
        if (ownerId == null || locationId == null || skuCodes == null || today == null) {
            throw new IllegalArgumentException("Stock allocation supply scope is required");
        }
        // 先保留所有需求 SKU 的空集合，讓「查無供給」仍是完整且可驗證的 projection。
        Map<String, List<StockQuantSupply>> grouped = new LinkedHashMap<>();
        skuCodes.forEach(skuCode -> grouped.put(skuCode, new ArrayList<>()));
        if (!grouped.isEmpty()) {
            jdbcClient
                    .sql(SQL)
                    .param("ownerId", ownerId)
                    .param("locationId", locationId)
                    .param("skuCodes", grouped.keySet())
                    .param("today", today)
                    .query(JdbcStockAllocationSupplyStoreAdapter::mapSupply)
                    .list()
                    // SQL 已依 SKU、效期、入庫日與 ID 排成穩定 FEFO 順序。
                    .forEach(supply -> grouped.get(supply.skuCode()).add(supply));
        }
        return StockAllocationSupply.of(ownerId, locationId, grouped);
    }

    private static StockQuantSupply mapSupply(ResultSet row, int rowNumber) throws SQLException {
        int availableToPromise = Math.subtractExact(row.getInt("on_hand_quantity"), row.getInt("reserved_quantity"));
        return new StockQuantSupply(
                row.getObject("id", UUID.class),
                row.getObject("owner_id", UUID.class),
                row.getObject("location_id", UUID.class),
                row.getString("sku_code"),
                row.getObject("in_date", LocalDate.class),
                row.getObject("expiry_date", LocalDate.class),
                availableToPromise);
    }
}
