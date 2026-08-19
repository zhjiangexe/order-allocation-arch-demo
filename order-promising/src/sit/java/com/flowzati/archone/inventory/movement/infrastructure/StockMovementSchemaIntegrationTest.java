package com.flowzati.archone.inventory.movement.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.testsupport.OrderFixtures;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 搬運 schema 的形狀斷言。
 *
 * <p>不經 JPA entity，直接查 {@code information_schema}——形狀錯了就失敗，不會被 entity 的
 * 對應關係遮掉。
 *
 * <p>這一組守的東西可以歸成三句：**搬運的兩端都要有**、**單據不承載數量**、以及**預留不再是
 * 另一本帳**。
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@ActiveProfiles("test")
@Import(PostgreSQLTestConfiguration.class)
@DisplayName("Stock movement schema")
class StockMovementSchemaIntegrationTest {

    private static final UUID OWNER_ID = uuid(1);
    private static final UUID WAREHOUSE_ID = uuid(2);
    private static final UUID INTERNAL_LOCATION_ID = uuid(3);
    private static final UUID CUSTOMER_LOCATION_ID = uuid(4);
    private static final UUID PICKING_TYPE_ID = uuid(5);
    private static final UUID ORDER_ID = uuid(6);
    private static final UUID ORDER_LINE_ID = uuid(7);
    private static final UUID STOCK_QUANT_ID = uuid(8);
    private static final UUID ALLOCATION_DEMAND_ID = uuid(12);
    private static final UUID ALLOCATION_DEMAND_LINE_ID = uuid(13);
    private static final String SKU = "SKU-A";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Nested
    @DisplayName("預留不再是另一本帳")
    class ReservationsAreGone {

        @Test
        @DisplayName("stock_reservations 應已不存在，由 stock_move_lines 取代")
        void replacesReservationsWithMoveLines() {
            assertThat(tableNames())
                    .contains("stock_picking_types", "stock_pickings", "stock_moves", "stock_move_lines")
                    .doesNotContain("stock_reservations");
        }

        @Test
        @DisplayName("move line 不帶自己的狀態——它的狀態就是所屬 move 的狀態")
        void moveLineCarriesNoStateOfItsOwn() {
            // 多一個狀態欄位等於多一組要對齊的真相。釋放是刪除這一列，不是把它標成已釋放。
            assertThat(columnNames("stock_move_lines"))
                    .contains("move_id", "stock_pool_id", "quantity")
                    .doesNotContain("state", "status");
        }
    }

    @Nested
    @DisplayName("搬運的兩端都要有")
    class BothEnds {

        @Test
        @DisplayName("move 缺目的地時被拒絕")
        void rejectsAMoveWithoutADestination() {
            seed();
            assertThatThrownBy(() -> jdbcTemplate.update(
                            "INSERT INTO stock_moves (id, picking_id, owner_id, sku_code, from_location_id, "
                                    + "to_location_id, allocation_demand_id, allocation_demand_line_id, "
                                    + "source_line_id, order_line_id, demand_quantity, state, created_at, version) "
                                    + "VALUES (?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, 3, 'CONFIRMED', "
                                    + "CURRENT_TIMESTAMP, 0)",
                            uuid(20),
                            pickingId(),
                            OWNER_ID,
                            SKU,
                            INTERNAL_LOCATION_ID,
                            ALLOCATION_DEMAND_ID,
                            ALLOCATION_DEMAND_LINE_ID,
                            ORDER_LINE_ID.toString(),
                            ORDER_LINE_ID))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("出庫的目的地是虛擬位置，不得被 internal-only 擋下")
        void allowsAVirtualDestination() {
            // 庫存只存在於內部位置，但搬運的一端經常在公司之外。把 stock_pools 那條約束複製到
            // 這裡會擋掉出庫本身。
            seed();
            insertMove(uuid(21), INTERNAL_LOCATION_ID, CUSTOMER_LOCATION_ID, ORDER_LINE_ID, "CONFIRMED");

            assertThat(moveCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("入庫的來源是虛擬位置，且沒有訂單行")
        void allowsAVirtualSourceWithNoOrderLine() {
            // 第三個 change 的入庫 move 背後沒有任何訂單行。現在就可空，比屆時放寬乾淨。
            seed();
            insertMove(uuid(22), supplierLocationId(), INTERNAL_LOCATION_ID, null, "CONFIRMED");

            assertThat(moveCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("狀態的值域")
    class MoveState {

        @Test
        @DisplayName("四個狀態都寫得進去，含還沒有產生者的 DONE")
        void acceptsAllFourStates() {
            // DONE 現在沒有產生者，但值域要一次寫對：待配需求的謂詞是「有沒有 move」，而已完成
            // 的 move 也算有。少了它，R7 每一張已出貨的單都會重新變成待接手的需求。
            seed();
            insertMove(uuid(30), INTERNAL_LOCATION_ID, CUSTOMER_LOCATION_ID, ORDER_LINE_ID, "CONFIRMED");
            insertMove(uuid(31), INTERNAL_LOCATION_ID, CUSTOMER_LOCATION_ID, null, "ASSIGNED");
            insertMove(uuid(32), INTERNAL_LOCATION_ID, CUSTOMER_LOCATION_ID, null, "DONE");
            insertMove(uuid(33), INTERNAL_LOCATION_ID, CUSTOMER_LOCATION_ID, null, "CANCELLED");

            assertThat(moveCount()).isEqualTo(4);
        }

        @Test
        @DisplayName("等上一段的狀態不在值域裡——沒有上一段")
        void rejectsWaiting() {
            // WAITING 與依賴關係表是同一件事的兩半，一起到來。
            seed();
            assertThatThrownBy(() -> insertMove(uuid(34), INTERNAL_LOCATION_ID, CUSTOMER_LOCATION_ID, null, "WAITING"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Nested
    @DisplayName("Allocation demand execution reference")
    class AllocationExecutionReference {

        @Test
        @DisplayName("一條 allocation demand line 最多只能建立一筆 move")
        void rejectsDuplicateMoveForOneAllocationDemandLine() {
            seed();
            insertMove(uuid(35), INTERNAL_LOCATION_ID, CUSTOMER_LOCATION_ID, ORDER_LINE_ID, "CONFIRMED");

            assertThatThrownBy(() -> insertMove(
                            uuid(36), INTERNAL_LOCATION_ID, CUSTOMER_LOCATION_ID, ORDER_LINE_ID, "CONFIRMED"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Nested
    @DisplayName("作業單狀態的值域")
    class PickingState {

        @Test
        @DisplayName("目前可達的四個狀態都寫得進去")
        void acceptsAllReachableStates() {
            seed();
            for (String state : List.of("CONFIRMED", "ASSIGNED", "DONE", "CANCELLED")) {
                jdbcTemplate.update("UPDATE stock_pickings SET state = ? WHERE id = ?", state, pickingId());
                assertThat(jdbcTemplate.queryForObject(
                                "SELECT state FROM stock_pickings WHERE id = ?", String.class, pickingId()))
                        .isEqualTo(state);
            }
        }

        @Test
        @DisplayName("值域外的作業單狀態被拒絕")
        void rejectsUnknownState() {
            seed();
            assertThatThrownBy(() -> jdbcTemplate.update(
                            "UPDATE stock_pickings SET state = 'PICKING' WHERE id = ?", pickingId()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Nested
    @DisplayName("時間戳")
    class Timestamps {

        @Test
        @DisplayName("還在等貨時不得有配到的時刻")
        void rejectsAnAssignedTimeWhileStillWaiting() {
            // 一個沒有被約束綁住的時間戳，遲早會出現「狀態說配到了，時刻卻是空的」這種對不起來
            // 的列——orders 的狀態與時間戳也是這樣綁的。
            seed();
            assertThatThrownBy(() -> jdbcTemplate.update(
                            "INSERT INTO stock_moves (id, picking_id, owner_id, sku_code, from_location_id, "
                                    + "to_location_id, demand_quantity, state, created_at, assigned_at, version) "
                                    + "VALUES (?, ?, ?, ?, ?, ?, 3, 'CONFIRMED', CURRENT_TIMESTAMP, "
                                    + "CURRENT_TIMESTAMP, 0)",
                            uuid(60),
                            pickingId(),
                            OWNER_ID,
                            SKU,
                            INTERNAL_LOCATION_ID,
                            CUSTOMER_LOCATION_ID))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("配到了卻沒有配到的時刻，同樣被拒絕")
        void rejectsAnAssignedMoveWithoutATime() {
            seed();
            assertThatThrownBy(() -> jdbcTemplate.update(
                            "INSERT INTO stock_moves (id, picking_id, owner_id, sku_code, from_location_id, "
                                    + "to_location_id, demand_quantity, state, created_at, version) "
                                    + "VALUES (?, ?, ?, ?, ?, ?, 3, 'ASSIGNED', CURRENT_TIMESTAMP, 0)",
                            uuid(61),
                            pickingId(),
                            OWNER_ID,
                            SKU,
                            INTERNAL_LOCATION_ID,
                            CUSTOMER_LOCATION_ID))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("明細沒有自己的時間戳——它的時間就是所屬搬運被配到的時刻")
        void moveLineHasNoTimeOfItsOwn() {
            assertThat(columnNames("stock_move_lines"))
                    .doesNotContain("created_at", "assigned_at", "date", "reserved_at");
        }
    }

    @Nested
    @DisplayName("單據不承載數量，也不承載訂單")
    class PickingIsTaskTruthOnly {

        @Test
        @DisplayName("move 可獨立存在，picking 不是強制容器")
        void allowsAStandaloneMoveWithoutAPicking() {
            seed();

            jdbcTemplate.update(
                    "INSERT INTO stock_moves (id, picking_id, owner_id, sku_code, from_location_id, "
                            + "to_location_id, demand_quantity, state, created_at, version) "
                            + "VALUES (?, NULL, ?, ?, ?, ?, 3, 'CONFIRMED', CURRENT_TIMESTAMP, 0)",
                    uuid(70),
                    OWNER_ID,
                    SKU,
                    INTERNAL_LOCATION_ID,
                    CUSTOMER_LOCATION_ID);

            assertThat(jdbcTemplate.queryForObject(
                            "SELECT picking_id FROM stock_moves WHERE id = ?", UUID.class, uuid(70)))
                    .isNull();
        }

        @Test
        @DisplayName("picking 沒有 SKU、沒有數量")
        void carriesNoQuantity() {
            assertThat(columnNames("stock_pickings"))
                    .contains("picking_type_id", "owner_id", "from_location_id", "to_location_id")
                    .doesNotContain("sku_code", "quantity", "demand_quantity");
        }

        @Test
        @DisplayName("picking 帶 order_id，但沒有指向 orders 的外鍵")
        void carriesTheOrderItServesWithoutDependingOnOrderingSchema() {
            // 不是捷徑：本系統不跨單合併，一張出庫單就是一張 picking，所以它是單據的身分。
            // 配貨需要它——ship-complete 的整單判斷與帶 orderId 的結果事件都要，而 move 只有
            // order_line_id，從行推到單得 join order_lines，那是邊界禁止的。
            assertThat(columnNames("stock_pickings")).contains("order_id");

            // 但不建外鍵：執行層的 schema 不依賴需求層的表。完整性由 move 的 order_line_id 保證。
            assertThat(foreignKeyTargetsOf("stock_pickings")).doesNotContain("orders");
        }

        @Test
        @DisplayName("picking 物化 moves 的摘要狀態，並用 version 防止配貨與取消互相覆蓋")
        void carriesStateAndOptimisticLockVersion() {
            assertThat(columnNames("stock_pickings")).contains("state", "version");
        }

        @Test
        @DisplayName("picking 沒有參照與排程日——兩者都沒有讀者")
        void carriesNoUnreadFields() {
            // 沒有任何畫面顯示單據，佇列的排序也用搬運的到達順序而不是排程日。等真的有單據畫面
            // 時再加，屆時它們會帶著讀取端一起進來。
            assertThat(columnNames("stock_pickings")).doesNotContain("reference", "scheduled_at");
        }

        @Test
        @DisplayName("move 沒有 previous_move_id——線性假設不進 schema")
        void doesNotChainMovesLinearly() {
            // 一筆補貨支撐多個下游、多來源匯入一個下游、拆分與部分完成，任何一個出現都會讓
            // 單一前驅表達不了。串接真的出現時建關聯表。
            assertThat(columnNames("stock_moves")).doesNotContain("previous_move_id", "next_move_id");
        }
    }

    @Nested
    @DisplayName("作業類型")
    class PickingTypes {

        @Test
        @DisplayName("三種作業方向都寫得進去")
        void acceptsThreeDirections() {
            seedCatalogOnly();
            insertPickingType(uuid(40), "INBOUND", supplierLocationId(), INTERNAL_LOCATION_ID);
            insertPickingType(uuid(41), "OUTBOUND", INTERNAL_LOCATION_ID, CUSTOMER_LOCATION_ID);
            insertPickingType(uuid(42), "INTERNAL", INTERNAL_LOCATION_ID, INTERNAL_LOCATION_ID);

            assertThat(pickingTypeCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("值域外的作業方向被拒絕")
        void rejectsUnknownCode() {
            seedCatalogOnly();
            assertThatThrownBy(() ->
                            insertPickingType(uuid(43), "MANUFACTURING", INTERNAL_LOCATION_ID, INTERNAL_LOCATION_ID))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("不產生單號，因此沒有序號欄位")
        void carriesNoSequence() {
            assertThat(columnNames("stock_picking_types")).doesNotContain("sequence_code", "sequence_id");
        }
    }

    // ---- fixtures ----

    private void seedCatalogOnly() {
        jdbcTemplate.update("INSERT INTO owners (id, code, name) VALUES (?, 'OWNER-A', 'A')", OWNER_ID);
        jdbcTemplate.update(
                "INSERT INTO products (id, owner_id, product_code, name, temperature_zone) "
                        + "VALUES (?, ?, 'P-A', 'P', 'AMBIENT')",
                uuid(10),
                OWNER_ID);
        jdbcTemplate.update(
                "INSERT INTO skus (id, owner_id, sku_code, product_code, spec_name, weight_gram) "
                        + "VALUES (?, ?, ?, 'P-A', 'spec', 100)",
                uuid(11),
                OWNER_ID,
                SKU);
        jdbcTemplate.update("INSERT INTO facilities (id, code, name) VALUES (?, 'WH-A', 'A')", WAREHOUSE_ID);
        jdbcTemplate.update(
                "INSERT INTO owner_facilities (owner_id, facility_id) VALUES (?, ?)", OWNER_ID, WAREHOUSE_ID);
        jdbcTemplate.update(
                "INSERT INTO stock_locations (id, facility_id, code, name, usage) "
                        + "VALUES (?, ?, 'WH-A/Stock', 'WH-A/Stock', 'INTERNAL')",
                INTERNAL_LOCATION_ID,
                WAREHOUSE_ID);
        jdbcTemplate.update(
                "INSERT INTO stock_locations (id, facility_id, code, name, usage) "
                        + "VALUES (?, NULL, 'Customers', 'Customers', 'CUSTOMER')",
                CUSTOMER_LOCATION_ID);
        jdbcTemplate.update(
                "INSERT INTO stock_locations (id, facility_id, code, name, usage) "
                        + "VALUES (?, NULL, 'Vendors', 'Vendors', 'SUPPLIER')",
                supplierLocationId());
    }

    private void seed() {
        seedCatalogOnly();
        insertPickingType(PICKING_TYPE_ID, "OUTBOUND", INTERNAL_LOCATION_ID, CUSTOMER_LOCATION_ID);
        jdbcTemplate.update(
                "INSERT INTO orders (id, owner_id, external_order_no, ship_to_zone, ship_to_address, "
                        + "promised_delivery_date, dispatch_by, release_priority, facility_id, status, "
                        + "received_at, version) "
                        + "VALUES (?, ?, 'EXT-1', 'Z', 'addr', ?, ?, 50, ?, 'PENDING', ?, 0)",
                ORDER_ID,
                OWNER_ID,
                Date.valueOf(LocalDate.of(2026, 12, 31)),
                Timestamp.from(OrderFixtures.DISPATCH_BY),
                WAREHOUSE_ID,
                Timestamp.from(Instant.now()));
        jdbcTemplate.update(
                "INSERT INTO order_lines (id, order_id, line_no, owner_id, sku_code, quantity) "
                        + "VALUES (?, ?, 1, ?, ?, 3)",
                ORDER_LINE_ID,
                ORDER_ID,
                OWNER_ID,
                SKU);
        jdbcTemplate.update(
                """
        INSERT INTO allocation_demands
            (id, source_type, source_id, allocation_unit_key, owner_id, facility_id,
             location_id, required_by, release_priority, enqueued_at,
             accepted_content_version, status, version)
        VALUES (?, 'ORDER', ?, 'PRIMARY', ?, ?, ?, ?, 50, CURRENT_TIMESTAMP, 1, 'PENDING', 0)
        """,
                ALLOCATION_DEMAND_ID,
                ORDER_ID.toString(),
                OWNER_ID,
                WAREHOUSE_ID,
                INTERNAL_LOCATION_ID,
                Timestamp.from(OrderFixtures.DISPATCH_BY));
        jdbcTemplate.update("""
        INSERT INTO allocation_demand_lines
            (id, allocation_demand_id, source_line_id, sku_code, quantity, line_sequence)
        VALUES (?, ?, ?, ?, 3, 1)
        """, ALLOCATION_DEMAND_LINE_ID, ALLOCATION_DEMAND_ID, ORDER_LINE_ID.toString(), SKU);
        jdbcTemplate.update(
                "INSERT INTO stock_pools (id, owner_id, location_id, sku_code, in_date, expiry_date, "
                        + "on_hand_quantity, reserved_quantity, version) VALUES (?, ?, ?, ?, ?, ?, 10, 0, 0)",
                STOCK_QUANT_ID,
                OWNER_ID,
                INTERNAL_LOCATION_ID,
                SKU,
                Date.valueOf(LocalDate.of(2026, 1, 1)),
                Date.valueOf(LocalDate.of(2027, 1, 1)));
        jdbcTemplate.update(
                "INSERT INTO stock_pickings (id, picking_type_id, owner_id, order_id, from_location_id, "
                        + "to_location_id, dispatch_by, release_priority) VALUES (?, ?, ?, ?, ?, ?, ?, 50)",
                pickingId(),
                PICKING_TYPE_ID,
                OWNER_ID,
                ORDER_ID,
                INTERNAL_LOCATION_ID,
                CUSTOMER_LOCATION_ID,
                Timestamp.from(OrderFixtures.DISPATCH_BY));
    }

    private void insertPickingType(UUID id, String code, UUID from, UUID to) {
        jdbcTemplate.update(
                "INSERT INTO stock_picking_types (id, facility_id, code, name, "
                        + "default_from_location_id, default_to_location_id) VALUES (?, ?, ?, ?, ?, ?)",
                id,
                WAREHOUSE_ID,
                code,
                code,
                from,
                to);
    }

    private void insertMove(UUID id, UUID from, UUID to, UUID orderLineId, String state) {
        jdbcTemplate.update(
                "INSERT INTO stock_moves (id, picking_id, owner_id, sku_code, from_location_id, "
                        + "to_location_id, allocation_demand_id, allocation_demand_line_id, source_line_id, "
                        + "order_line_id, demand_quantity, state, created_at, assigned_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 3, ?, ?, ?, 0)",
                id,
                pickingId(),
                OWNER_ID,
                SKU,
                from,
                to,
                orderLineId == null ? null : ALLOCATION_DEMAND_ID,
                orderLineId == null ? null : ALLOCATION_DEMAND_LINE_ID,
                orderLineId == null ? null : orderLineId.toString(),
                orderLineId,
                state,
                Timestamp.from(Instant.now()),
                "CONFIRMED".equals(state) || "CANCELLED".equals(state) ? null : Timestamp.from(Instant.now()));
    }

    private static UUID pickingId() {
        return uuid(9);
    }

    private static UUID supplierLocationId() {
        return uuid(12);
    }

    private int moveCount() {
        Integer c = jdbcTemplate.queryForObject("SELECT count(*) FROM stock_moves", Integer.class);
        return c == null ? 0 : c;
    }

    private int pickingTypeCount() {
        Integer c = jdbcTemplate.queryForObject("SELECT count(*) FROM stock_picking_types", Integer.class);
        return c == null ? 0 : c;
    }

    private List<String> foreignKeyTargetsOf(String table) {
        return jdbcTemplate.queryForList(
                "SELECT ccu.table_name FROM information_schema.table_constraints tc "
                        + "JOIN information_schema.constraint_column_usage ccu "
                        + "  ON ccu.constraint_name = tc.constraint_name "
                        + "WHERE tc.table_name = ? AND tc.constraint_type = 'FOREIGN KEY'",
                String.class,
                table);
    }

    private List<String> tableNames() {
        return jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);
    }

    private List<String> columnNames(String table) {
        return jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ?",
                String.class,
                table);
    }

    private static UUID uuid(int seed) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", seed));
    }
}
