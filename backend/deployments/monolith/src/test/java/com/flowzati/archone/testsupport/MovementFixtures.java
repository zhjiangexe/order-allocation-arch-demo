package com.flowzati.archone.testsupport;

import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.inventory.location.domain.entity.StockLocation;
import com.flowzati.archone.inventory.location.domain.valueobject.LocationUsageType;
import com.flowzati.archone.inventory.movement.domain.entity.StockOperationType;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationDirection;
import com.flowzati.archone.ordering.domain.aggregate.Order;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * 搬運與作業類型的測試資料。
 *
 * <p>與 {@link OrderFixtures} 共用同一組倉與位置常數——搬運的起點就是庫存所在的位置，兩邊各寫
 * 一組 UUID 的話，其中一邊改了另一邊沒改，症狀會是「單建好了卻配不到」而不是編譯錯誤。
 *
 * <p><b>終點是虛擬的客戶位置，不屬於任何倉。</b>它與 {@link OrderFixtures#LOCATION_ID} 刻意
 * 不同值：出庫的兩端一個在倉內、一個在公司之外，寫成同一個值會讓「兩端都要有」這件事失去
 * 意義——一段起訖相同的搬運不移動任何東西。
 */
public final class MovementFixtures {

    /** 客戶。虛擬位置，所有出庫的終點。 */
    public static final UUID CUSTOMERS_LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    /** 供應商。虛擬位置，入庫的起點——第三個 change 才會有讀者，但值域一次定完。 */
    public static final UUID SUPPLIERS_LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d2");

    /** 測試倉的出庫作業類型。 */
    public static final UUID OUTBOUND_TYPE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    /** 第二個倉的出庫作業類型。跨倉的測試要它，否則第二個倉的單無處可去。 */
    public static final UUID OTHER_OUTBOUND_TYPE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e2");
    /** 測試倉的入庫作業類型：供應商 → 庫存位置。 */
    public static final UUID INBOUND_TYPE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e3");

    public static final UUID OTHER_INBOUND_TYPE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e4");

    private MovementFixtures() {}

    /** 測試倉的出庫類型：庫存位置 → 客戶。 */
    public static StockOperationType outboundType() {
        return outboundTypeAt(OUTBOUND_TYPE_ID, OrderFixtures.FACILITY_ID, OrderFixtures.LOCATION_ID);
    }

    public static StockOperationType outboundTypeAt(UUID id, UUID facilityId, UUID stockLocationId) {
        return new StockOperationType(
                id, facilityId, StockOperationDirection.OUTBOUND, "出貨", stockLocationId, CUSTOMERS_LOCATION_ID);
    }

    /** 測試倉的入庫類型：供應商 → 庫存位置。方向與出庫相反。 */
    public static StockOperationType inboundType() {
        return new StockOperationType(
                INBOUND_TYPE_ID,
                OrderFixtures.FACILITY_ID,
                StockOperationDirection.INBOUND,
                "收貨",
                SUPPLIERS_LOCATION_ID,
                OrderFixtures.LOCATION_ID);
    }

    /** 供應商位置。入庫的起點，不屬於任何倉。 */
    public static StockLocation suppliersLocation() {
        return StockLocation.virtual(
                SUPPLIERS_LOCATION_ID, "FIXTURE/Vendors", "共用 fixture 的供應商", LocationUsageType.SUPPLIER);
    }

    /** 客戶位置。出庫的終點，不屬於任何倉。 */
    public static StockLocation customersLocation() {
        return StockLocation.virtual(
                CUSTOMERS_LOCATION_ID, "FIXTURE/Customers", "共用 fixture 的客戶", LocationUsageType.CUSTOMER);
    }

    /** 測試倉的內部位置。usecase 要靠它從位置反查倉。 */
    public static StockLocation internalLocation() {
        return StockLocation.internal(OrderFixtures.LOCATION_ID, OrderFixtures.FACILITY_ID, "WH-TEST/Stock", "測試倉／庫存");
    }

    // ---------------------------------------------------------------------------------------
    // 整合測試用：直接以 SQL 造出／讀出搬運
    // ---------------------------------------------------------------------------------------

    /** 這張單目前鎖住的一筆量。取代舊的「一筆有效預留」。 */
    public record HeldQuantity(UUID stockQuantId, int quantity) {}

    /** 單行 scenario 的 canonical identity 對照：source → operation → move → move line。 */
    public record AssignedPickingScenario(UUID sourceId, UUID sourceLineId, UUID stockOperationId, UUID movementId) {}

    /** Seeds one confirmed operation and one confirmed move per order line. */
    public static UUID seedConfirmedPicking(JdbcTemplate jdbcTemplate, Order order) {
        return inTransaction(jdbcTemplate, transaction -> seedPicking(transaction, order, "CONFIRMED"));
    }

    /** 一張已進入 assignment queue 的單：訂單 source 與 canonical confirmed movements。 */
    public static Order saveConfirmedPickingOrder(
            OrderRepository orderRepository, JdbcTemplate jdbcTemplate, Order order) {
        orderRepository.save(order);
        seedConfirmedPicking(jdbcTemplate, order);
        return order;
    }

    /** 一張已配到貨的單：assigned operation/move + 指向庫存批次的 move line。 */
    public static AssignedPickingScenario seedAssignedPicking(
            JdbcTemplate jdbcTemplate, Order order, UUID stockQuantId, int quantity) {
        return inTransaction(
                jdbcTemplate, transaction -> createAssignedPicking(transaction, order, stockQuantId, quantity));
    }

    private static AssignedPickingScenario createAssignedPicking(
            JdbcTemplate jdbcTemplate, Order order, UUID stockQuantId, int quantity) {
        UUID stockOperationId = seedPicking(jdbcTemplate, order, "ASSIGNED");
        UUID moveId = IdGenerator.nextId();
        var line = order.getLines().getFirst();
        jdbcTemplate.update("DELETE FROM stock_moves WHERE stock_operation_id = ?", stockOperationId);
        jdbcTemplate.update(
                """
        INSERT INTO stock_moves
            (id, stock_operation_id, owner_id, sku_code, from_location_id, to_location_id,
             source_line_id, line_sequence, demand_quantity, state, created_at, assigned_at, version)
        VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, 'ASSIGNED', ?, ?, 0)
        """,
                moveId,
                stockOperationId,
                order.getOwnerId(),
                line.getSkuCode(),
                OrderFixtures.LOCATION_ID,
                CUSTOMERS_LOCATION_ID,
                line.getId().toString(),
                line.getQuantity(),
                java.sql.Timestamp.from(order.getReceivedAt()),
                java.sql.Timestamp.from(order.getReceivedAt()));
        jdbcTemplate.update("""
        INSERT INTO stock_move_lines (id, move_id, stock_pool_id, quantity)
        VALUES (?, ?, ?, ?)
        """, IdGenerator.nextId(), moveId, stockQuantId, quantity);
        int updated = jdbcTemplate.update(
                "UPDATE stock_pools SET reserved_quantity = reserved_quantity + ?, version = version + 1 WHERE id = ?",
                quantity,
                stockQuantId);
        if (updated != 1) {
            throw new IllegalArgumentException("Stock quant does not exist: " + stockQuantId);
        }
        return new AssignedPickingScenario(order.getId(), line.getId(), stockOperationId, moveId);
    }

    private static UUID seedPicking(JdbcTemplate jdbcTemplate, Order order, String state) {
        UUID stockOperationId = IdGenerator.nextId();
        UUID facilityId = order.getDeliveryTerms().facilityId();
        UUID fromLocationId;
        UUID stockOperationTypeId;
        if (facilityId.equals(OrderFixtures.FACILITY_ID)) {
            fromLocationId = OrderFixtures.LOCATION_ID;
            stockOperationTypeId = OUTBOUND_TYPE_ID;
        } else if (facilityId.equals(OrderFixtures.OTHER_FACILITY_ID)) {
            fromLocationId = OrderFixtures.OTHER_LOCATION_ID;
            stockOperationTypeId = OTHER_OUTBOUND_TYPE_ID;
        } else {
            throw new IllegalArgumentException("No fixture operation type for warehouse " + facilityId);
        }
        jdbcTemplate.update(
                """
        INSERT INTO stock_operations
            (id, stock_operation_type_id, direction, owner_id, from_location_id, to_location_id,
             source_type, source_id, allocation_unit_key, policy_code, enqueued_at,
             dispatch_by, release_priority, state, version)
        VALUES (?, ?, 'OUTBOUND', ?, ?, ?, 'ORDER', ?, 'PRIMARY', 'SHIP_COMPLETE', ?, ?, ?, ?, 0)
        """,
                stockOperationId,
                stockOperationTypeId,
                order.getOwnerId(),
                fromLocationId,
                CUSTOMERS_LOCATION_ID,
                order.getId().toString(),
                java.sql.Timestamp.from(order.getReceivedAt()),
                java.sql.Timestamp.from(order.getDeliveryTerms().dispatchBy()),
                order.getDeliveryTerms().releasePriority(),
                state);

        java.util.List<com.flowzati.archone.ordering.domain.entity.OrderLine> canonical = order.getLines().stream()
                .sorted(java.util.Comparator.comparing(line -> line.getId().toString()))
                .toList();
        for (int index = 0; index < canonical.size(); index++) {
            var line = canonical.get(index);
            jdbcTemplate.update(
                    """
          INSERT INTO stock_moves
              (id, stock_operation_id, owner_id, sku_code, from_location_id, to_location_id,
               source_line_id, line_sequence, demand_quantity, state, created_at, assigned_at, version)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
          """,
                    IdGenerator.nextId(),
                    stockOperationId,
                    order.getOwnerId(),
                    line.getSkuCode(),
                    fromLocationId,
                    CUSTOMERS_LOCATION_ID,
                    line.getId().toString(),
                    index + 1,
                    line.getQuantity(),
                    state,
                    java.sql.Timestamp.from(order.getReceivedAt()),
                    "ASSIGNED".equals(state) ? java.sql.Timestamp.from(order.getReceivedAt()) : null);
        }
        return stockOperationId;
    }

    private static <T> T inTransaction(JdbcTemplate jdbcTemplate, TransactionWork<T> work) {
        return jdbcTemplate.execute((ConnectionCallback<T>) connection -> {
            boolean ownsTransaction = connection.getAutoCommit();
            if (ownsTransaction) {
                connection.setAutoCommit(false);
            }
            JdbcTemplate transaction = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            try {
                T result = work.execute(transaction);
                if (ownsTransaction) {
                    connection.commit();
                }
                return result;
            } catch (RuntimeException | java.sql.SQLException failure) {
                if (ownsTransaction) {
                    connection.rollback();
                }
                throw failure;
            } finally {
                if (ownsTransaction) {
                    connection.setAutoCommit(true);
                }
            }
        });
    }

    @FunctionalInterface
    private interface TransactionWork<T> {

        T execute(JdbcTemplate jdbcTemplate);
    }

    /**
     * 這張單目前由 canonical commitment 鎖住了哪些量。
     *
     * <p>回清單而不是單筆：一個 move 可由多個 move lines 跨批覆蓋。
     */
    public static List<HeldQuantity> heldBy(JdbcTemplate jdbcTemplate, UUID orderId) {
        return jdbcTemplate.query(
                """
        SELECT move_line.stock_pool_id, move_line.quantity
          FROM stock_move_lines move_line
          JOIN stock_moves move ON move.id = move_line.move_id
          JOIN stock_operations operation ON operation.id = move.stock_operation_id
         WHERE operation.source_type = 'ORDER'
           AND operation.source_id = ?
           AND move.state = 'ASSIGNED'
         ORDER BY move_line.quantity DESC, move_line.stock_pool_id
        """,
                (rs, rowNum) -> new HeldQuantity(rs.getObject("stock_pool_id", UUID.class), rs.getInt("quantity")),
                orderId.toString());
    }

    /** 這張單每一段搬運的狀態，依建立順序。 */
    public static List<String> moveStatesOf(JdbcTemplate jdbcTemplate, UUID orderId) {
        return jdbcTemplate.queryForList("""
        SELECT m.state
          FROM stock_moves m
          JOIN stock_operations operation ON operation.id = m.stock_operation_id
         WHERE operation.source_type = 'ORDER'
           AND operation.source_id = ?
         ORDER BY m.line_sequence, m.id
        """, String.class, orderId.toString());
    }
}
