package com.flowzati.archone.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.ArchoneApplication;
import com.flowzati.archone.catalog.domain.aggregate.Facility;
import com.flowzati.archone.catalog.domain.repository.FacilityRepository;
import com.flowzati.archone.catalog.domain.repository.OwnerRepository;
import com.flowzati.archone.foundation.time.BusinessClock;
import com.flowzati.archone.inventory.allocation.domain.repository.AllocationDemandRepository;
import com.flowzati.archone.inventory.allocation.domain.valueobject.WaitingAllocationScope;
import com.flowzati.archone.inventory.balance.domain.aggregate.StockQuant;
import com.flowzati.archone.inventory.balance.domain.repository.StockQuantRepository;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import com.flowzati.archone.ordering.domain.type.OrderStatus;
import com.flowzati.archone.testsupport.MovementFixtures;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import com.flowzati.archone.testsupport.SitDatabase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
        classes = ArchoneApplication.class,
        properties = "spring.kafka.listener.auto-startup=false",
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
@Import(PostgreSQLTestConfiguration.class)
class DevSeedDataIntegrationTest {

    @Autowired
    private DevSeedDataInitializer initializer;

    @Autowired
    private StockQuantRepository stockQuantRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private AllocationDemandRepository allocationDemandRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BusinessClock appClock;

    @Autowired
    private OwnerRepository ownerRepository;

    @Autowired
    private FacilityRepository facilityRepository;

    /**
     * 每支測試前重新 seed。
     *
     * <p>seed 本身是 {@code ApplicationRunner}，只在 context 啟動時跑一次；而下方的
     * {@code @AfterEach} 會清空資料庫，所以第二支之後的測試會看到空資料。這裡重跑一次，
     * 順帶讓「重跑不重複」這件事在每支測試都被走過一遍。
     */
    @BeforeEach
    void seedAgain() {
        initializer.run(null);
    }

    @AfterEach
    void clearDatabase() {
        SitDatabase.clear(jdbcTemplate);
    }

    @Test
    @DisplayName("dev seed 應建立一致資料且重跑不重複")
    void shouldCreateConsistentDevSeedDataWithoutDuplicatesOnRepeatRun() throws Exception {
        assertThat(batch(DevSeedDataInitializer.NEAR_EXPIRY_STOCK_QUANT_ID)).satisfies(pool -> {
            assertThat(pool.getOnHandQuantity()).isEqualTo(60);
            // 被跨批訂單全部吃掉：畫面上要有一個「配完的批」。
            assertThat(pool.getReservedQuantity()).isEqualTo(60);
        });
        assertThat(batch(DevSeedDataInitializer.MID_EXPIRY_EARLY_ARRIVAL_STOCK_QUANT_ID))
                .satisfies(pool -> {
                    assertThat(pool.getOnHandQuantity()).isEqualTo(40);
                    // 配一半的批：跨批訂單的第二段。
                    assertThat(pool.getReservedQuantity()).isEqualTo(20);
                });
        assertThat(batch(DevSeedDataInitializer.EMPTY_STOCK_QUANT_ID)).satisfies(pool -> {
            assertThat(pool.getOnHandQuantity()).isZero();
            assertThat(pool.getReservedQuantity()).isZero();
        });
        assertThat(batch(DevSeedDataInitializer.PARTIALLY_RESERVED_STOCK_QUANT_ID))
                .satisfies(pool -> {
                    assertThat(pool.getOnHandQuantity()).isEqualTo(20);
                    assertThat(pool.getReservedQuantity()).isEqualTo(5);
                });
        assertThat(orderRepository.findById(DevSeedDataInitializer.PARTIALLY_RESERVED_ORDER_ID))
                .hasValueSatisfying(order -> assertThat(order.getStatus()).isEqualTo(OrderStatus.ALLOCATED));
        assertThat(heldBy(DevSeedDataInitializer.PARTIALLY_RESERVED_ORDER_ID))
                .singleElement()
                .satisfies(held -> {
                    assertThat(held.quantity()).isEqualTo(5);
                    assertThat(held.stockQuantId()).isEqualTo(DevSeedDataInitializer.PARTIALLY_RESERVED_STOCK_QUANT_ID);
                });
        // 明細存在就代表鎖著——沒有狀態要驗，狀態在搬運上。
        assertThat(MovementFixtures.moveStatesOf(jdbcTemplate, DevSeedDataInitializer.PARTIALLY_RESERVED_ORDER_ID))
                .containsExactly("ASSIGNED");

        initializer.run(null);

        // 七批（近／中早／中晚／已過期／空／部分預留／乙貨主充足）、四張單、三條明細。
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stock_pools", Integer.class))
                .isEqualTo(7);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders", Integer.class))
                .isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stock_move_lines", Integer.class))
                .isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM owners", Integer.class))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("種子必須有同效期不同入庫日的兩批——少了它，FEFO 的 tie-breaker 完全沒被測到")
    void seedsTwoBatchesSharingAnExpiryDateButDifferingInArrival() {
        StockQuant early = batch(DevSeedDataInitializer.MID_EXPIRY_EARLY_ARRIVAL_STOCK_QUANT_ID);
        StockQuant late = batch(DevSeedDataInitializer.MID_EXPIRY_LATE_ARRIVAL_STOCK_QUANT_ID);

        assertThat(early.getExpiryDate()).isEqualTo(late.getExpiryDate());
        assertThat(early.getInDate()).isBefore(late.getInDate());
    }

    @Test
    @DisplayName("種子必須有一批已過期的貨——「有貨但配不到」在畫面上要看得見")
    void seedsAnExpiredBatchThatIsPresentButNotAllocatable() {
        StockQuant expired = batch(DevSeedDataInitializer.EXPIRED_STOCK_QUANT_ID);

        // 不刪除、不隱藏：倉庫裡真的有這 25 件，而它與「什麼都沒有」要引導出不同的動作。
        assertThat(expired.getOnHandQuantity()).isEqualTo(25);
        assertThat(expired.isExpired(appClock.today())).isTrue();
        assertThat(stockQuantRepository.findAllocatableBatchesInFefoOrder(
                        DevSeedDataInitializer.FIRST_OWNER_ID,
                        DevSeedDataInitializer.NORTH_FACILITY_ID,
                        DevSeedDataInitializer.AVAILABLE_SKU,
                        appClock.today()))
                .extracting(StockQuant::getId)
                .doesNotContain(DevSeedDataInitializer.EXPIRED_STOCK_QUANT_ID);
    }

    @Test
    @DisplayName("種子必須一張帶上游下單時刻、一張不帶——「上游沒送」在畫面上要有一列是空的")
    void seedsOneOrderWithAnUpstreamPlacedTimeAndOneWithout() {
        assertThat(orderRepository.findById(DevSeedDataInitializer.PARTIALLY_RESERVED_ORDER_ID))
                .hasValueSatisfying(order -> {
                    assertThat(order.getReceivedAt()).isNotNull();
                    assertThat(order.getPlacedAt()).isNotNull();
                    // 上游比我們早——兩者相同的話，畫面上分不出上游是真的送了還是我們補的。
                    assertThat(order.getPlacedAt()).isBefore(order.getReceivedAt());
                });

        assertThat(orderRepository.findById(DevSeedDataInitializer.BACKORDERED_ORDER_ID))
                .hasValueSatisfying(order -> {
                    assertThat(order.getReceivedAt()).isNotNull();
                    assertThat(order.getPlacedAt()).isNull();
                });
    }

    @Test
    @DisplayName("種子必須有一張需求跨兩批的訂單——多批取用與多筆預留唯一的資料來源")
    void seedsAnOrderWhoseDemandSpansTwoBatches() {
        assertThat(orderRepository.findById(DevSeedDataInitializer.SPANNING_ORDER_ID))
                .hasValueSatisfying(order -> {
                    assertThat(order.getStatus()).isEqualTo(OrderStatus.ALLOCATED);
                    assertThat(order.getDemandFor(DevSeedDataInitializer.AVAILABLE_SKU))
                            .isEqualTo(80);
                });

        // 80 件 = 近效期 60 + 中效期 20，所以是兩條明細，各指向不同的批。
        assertThat(heldBy(DevSeedDataInitializer.SPANNING_ORDER_ID))
                .hasSize(2)
                .extracting(held -> held.stockQuantId(), held -> held.quantity())
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(DevSeedDataInitializer.NEAR_EXPIRY_STOCK_QUANT_ID, 60),
                        org.assertj.core.groups.Tuple.tuple(
                                DevSeedDataInitializer.MID_EXPIRY_EARLY_ARRIVAL_STOCK_QUANT_ID, 20));
    }

    @Test
    @DisplayName("種子的缺貨訂單應真的在 FIFO 佇列裡——補貨要能喚醒它，否則它是一列死資料")
    void seedsABackorderThatReplenishmentCanActuallyWake() {
        // 這一條守的是「種子訂單不是 PENDING」。PENDING 在真實系統裡是收單到消費之間的過渡，
        // 固化成種子等於展示一個穩定狀態下不存在的東西；更糟的是那張單繞過下單 usecase 直接
        // 寫入、沒有 OrderPlaced 事件，配置端從不知道它存在。照操作台 README 的 demo 流程
        // 補貨後畫面毫無變化，看起來像壞掉。
        //
        // 佇列由 allocation-owned demand lifecycle 回答；訂單狀態與 picking.orderId 都不是 generic
        // predicate。這裡直接走 production demand-first candidate query。
        List<UUID> queuedOrders = allocationDemandRepository
                .findPendingCandidates(
                        new WaitingAllocationScope(
                                DevSeedDataInitializer.SECOND_OWNER_ID,
                                DevSeedDataInitializer.SOUTH_FACILITY_ID,
                                DevSeedDataInitializer.SOUTH_STOCK_LOCATION_ID,
                                DevSeedDataInitializer.EMPTY_SKU),
                        DevSeedDataInitializer.EMPTY_SKU,
                        1_000)
                .candidates()
                .stream()
                .map(demand -> UUID.fromString(demand.source().sourceId()))
                .toList();

        assertThat(queuedOrders)
                .containsExactlyInAnyOrder(
                        DevSeedDataInitializer.BACKORDERED_ORDER_ID, DevSeedDataInitializer.BASKET_ORDER_ID);

        // 缺貨對象的庫存池必須真的是空的，否則「試過、沒貨」這個狀態自相矛盾
        assertThat(batch(DevSeedDataInitializer.EMPTY_STOCK_QUANT_ID).availableToPromise())
                .isZero();
    }

    @Test
    @DisplayName("種子必須有一張「有貨卻不配」的多 SKU 單——ship-complete 在畫面上唯一的證據")
    void seedsAMultiSkuOrderBlockedByOneOfItsSkus() {
        assertThat(orderRepository.findById(DevSeedDataInitializer.BASKET_ORDER_ID))
                .hasValueSatisfying(order -> {
                    assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
                    assertThat(order.getDemand())
                            .containsOnlyKeys(DevSeedDataInitializer.AVAILABLE_SKU, DevSeedDataInitializer.EMPTY_SKU);
                });

        // 這一條才是重點：充足的那一行**一件都沒被鎖住**。整張配或整張不配，所以卡在 SKU-EMPTY
        // 的這張單不會為自己留下 SKU-AVAILABLE 的 5 件——那 5 件留給後面配得出去的單。
        assertThat(batch(DevSeedDataInitializer.SECOND_OWNER_AVAILABLE_STOCK_QUANT_ID))
                .satisfies(pool -> {
                    assertThat(pool.getOnHandQuantity()).isEqualTo(50);
                    assertThat(pool.getReservedQuantity()).isZero();
                });
        assertThat(heldBy(DevSeedDataInitializer.BASKET_ORDER_ID)).isEmpty();
    }

    @Test
    @DisplayName("兩個貨主應共用同一個 SKU 代碼，且指向完全不同的商品——這是撞號情境的最小再現")
    void seedsTheCrossOwnerSkuCodeCollision() {
        List<Map<String, Object>> collidingSkus = jdbcTemplate.queryForList("""
        SELECT o.code AS owner_code, s.spec_name, s.weight_gram, p.name AS product_name
        FROM skus s
        JOIN owners o ON o.id = s.owner_id
        JOIN products p ON p.owner_id = s.owner_id AND p.product_code = s.product_code
        WHERE s.sku_code = ?
        ORDER BY o.code
        """, DevSeedDataInitializer.AVAILABLE_SKU);

        assertThat(collidingSkus).hasSize(2);
        assertThat(collidingSkus).extracting(row -> row.get("owner_code")).containsExactly("OWNER-A", "OWNER-B");
        // 同一個代碼指的是兩件不同的商品，連重量都不同——R6 的成本函數會因此得出不同結果
        assertThat(collidingSkus).extracting(row -> row.get("product_name")).containsExactly("烏龍茶", "麥茶");
        assertThat(collidingSkus).extracting(row -> row.get("weight_gram")).containsExactly(520, 610);
    }

    @Test
    @DisplayName("兩貨主的倉庫指派應重疊但不相等，且有一個倉同時服務兩個貨主")
    void seedsOverlappingButUnequalWarehouseAssignments() {
        List<UUID> first = facilityIdsOf(DevSeedDataInitializer.FIRST_OWNER_ID);
        List<UUID> second = facilityIdsOf(DevSeedDataInitializer.SECOND_OWNER_ID);

        // 同一貨主有多個倉
        assertThat(first).hasSize(2);
        assertThat(second).hasSize(2);
        // 不同貨主的倉不同
        assertThat(first).isNotEqualTo(second);
        // 一個倉服務多個貨主——3PL 的定義性特徵。少了這條，一個「以倉庫而非指派關係做過濾」
        // 的錯誤實作會安靜通過，因為每個倉只屬於一個貨主時兩種寫法結果相同。
        assertThat(first).containsAnyElementsOf(second);
    }

    private List<UUID> facilityIdsOf(UUID ownerId) {
        return facilityRepository.findByOwner(ownerId).stream()
                .map(Facility::getId)
                .toList();
    }

    @Test
    @DisplayName("每個種子倉應恰有一個 internal 位置，且三種虛擬用途各恰有一列")
    void seedsOneInternalLocationPerWarehouseAndEveryVirtualLocation() {
        List<UUID> seededFacilityIds = jdbcTemplate.queryForList("SELECT id FROM facilities", UUID.class);
        assertThat(seededFacilityIds).isNotEmpty();

        // 每個倉恰有一個——不是「至少一個」。倉→位置的解析要是一次查表而不是不定的選擇。
        for (UUID facilityId : seededFacilityIds) {
            assertThat(internalLocationCountOf(facilityId))
                    .as("倉 %s 的 internal 位置數", facilityId)
                    .isEqualTo(1);
        }

        // 虛擬位置在這個階段沒有任何讀者——還沒有東西移動貨。它們現在就要在，是因為 usage 的
        // 值域必須一次定完：晚一步引入等於同時改 CHECK 約束與回頭補種子資料。
        assertThat(locationCountOfUsage("SUPPLIER")).isEqualTo(1);
        assertThat(locationCountOfUsage("CUSTOMER")).isEqualTo(1);
        assertThat(locationCountOfUsage("INVENTORY")).isEqualTo(1);
    }

    private int internalLocationCountOf(UUID facilityId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM stock_locations WHERE facility_id = ? AND usage = 'INTERNAL'",
                Integer.class,
                facilityId);
        return count == null ? 0 : count;
    }

    private int locationCountOfUsage(String usage) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM stock_locations WHERE usage = ?", Integer.class, usage);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("應同時有常溫與冷凍，且其中一款帶兩個重量不同的規格")
    void seedsBothTemperatureZonesAndATwoSpecificationProduct() {
        assertThat(jdbcTemplate.queryForList("SELECT DISTINCT temperature_zone FROM products", String.class))
                .containsExactlyInAnyOrder("AMBIENT", "FROZEN");

        // 款／規格兩層要在畫面上看得出來，就得有一款真的帶兩個規格
        assertThat(jdbcTemplate.queryForList(
                        """
        SELECT weight_gram FROM skus
        WHERE owner_id = ? AND product_code = ?
        ORDER BY sku_code
        """,
                        Integer.class,
                        DevSeedDataInitializer.FIRST_OWNER_ID,
                        DevSeedDataInitializer.AMBIENT_PRODUCT_CODE))
                .containsExactly(520, 1000);
    }

    /**
     * 這張單目前鎖住了哪些量。
     *
     * <p>路徑是作業單 → 搬運 → 明細，與 {@code CancelMovementsUsecase} 走同一條。回的是清單
     * 而不是單筆：一條行跨三批就有三條明細。
     */
    private java.util.List<MovementFixtures.HeldQuantity> heldBy(java.util.UUID orderId) {
        return MovementFixtures.heldBy(jdbcTemplate, orderId);
    }

    /** 依 id 取那一批。種子的日期相對於今天計算，所以用 id 取比用五維鍵拼出來可靠。 */
    private StockQuant batch(java.util.UUID stockQuantId) {
        return stockQuantRepository.findById(stockQuantId).orElseThrow();
    }
}
