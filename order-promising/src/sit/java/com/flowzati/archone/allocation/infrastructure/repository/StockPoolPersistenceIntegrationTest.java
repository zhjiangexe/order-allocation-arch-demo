package com.flowzati.archone.allocation.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.infrastructure.entity.StockPoolEntity;
import com.flowzati.archone.allocation.infrastructure.repository.jpa.JpaStockRepository;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest(
    properties = "spring.data.jpa.repositories.enabled=false",
    showSql = false
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@ActiveProfiles("test")
@Import({
    PostgreSQLTestConfiguration.class,
    StockPoolRepositoryImpl.class,
    StockPoolPersistenceIntegrationTest.RepositoryConfiguration.class
})
@DisplayName("StockPool PostgreSQL persistence adapter")
class StockPoolPersistenceIntegrationTest {

  private static final Instant OLD_UPDATED_AT = Instant.parse("2000-01-01T00:00:00Z");
  private static final UUID STOCK_POOL_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Autowired
  private JpaStockRepository jpaRepository;

  @Autowired
  private StockPoolRepositoryImpl repositoryAdapter;

  @Autowired
  private EntityManager entityManager;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("應依 SKU 與 ID 還原完整 StockPool domain model")
  void persistsAndRestoresStockPool() {
    StockPoolEntity saved = persistStockPool(STOCK_POOL_ID, "SKU-1", 10, 4);

    StockPool bySku = repositoryAdapter.findBySku("SKU-1").orElseThrow();
    StockPool byId = repositoryAdapter.findById(STOCK_POOL_ID).orElseThrow();

    assertThat(bySku.getId()).isEqualTo(STOCK_POOL_ID);
    assertThat(bySku.getSku()).isEqualTo("SKU-1");
    assertThat(bySku.getOnHandQuantity()).isEqualTo(10);
    assertThat(bySku.getReservedQuantity()).isEqualTo(4);
    assertThat(bySku.availableToPromise()).isEqualTo(6);
    assertThat(bySku.getVersion()).isEqualTo(saved.getVersion());
    assertThat(byId.getSku()).isEqualTo("SKU-1");
    assertThat(saved.getUpdatedAt()).isNotNull();
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @EnumSource(QuantityMutation.class)
  @DisplayName("reserve、release 與 replenish 儲存時都應更新 timestamp 與 version")
  void updatesTimestampAndVersionForEveryQuantityMutation(QuantityMutation mutation) {
    StockPoolEntity initial = persistStockPool(STOCK_POOL_ID, "SKU-1", 10, 2);
    Long initialVersion = initial.getVersion();
    setOldUpdatedAt(STOCK_POOL_ID);

    StockPool stockPool = repositoryAdapter.findById(STOCK_POOL_ID).orElseThrow();
    mutation.apply(stockPool);

    repositoryAdapter.save(stockPool);
    jpaRepository.flush();
    entityManager.clear();

    StockPoolEntity updated = jpaRepository.findById(STOCK_POOL_ID).orElseThrow();
    assertThat(updated.getOnHandQuantity()).isEqualTo(mutation.expectedOnHandQuantity);
    assertThat(updated.getReservedQuantity()).isEqualTo(mutation.expectedReservedQuantity);
    assertThat(updated.getUpdatedAt()).isAfter(OLD_UPDATED_AT);
    assertThat(updated.getVersion()).isEqualTo(initialVersion + 1);
  }

  @Test
  @DisplayName("stale StockPool snapshot 寫回時應被 optimistic locking 拒絕")
  void rejectsStaleVersion() {
    persistStockPool(STOCK_POOL_ID, "SKU-1", 10, 2);
    StockPool staleStockPool = repositoryAdapter.findById(STOCK_POOL_ID).orElseThrow();

    // 模擬另一個 transaction 已先更新同一筆 StockPool 並遞增 version。
    jdbcTemplate.update("""
        UPDATE stock_pools
        SET on_hand_quantity = 11,
            version = version + 1
        WHERE id = ?
        """, STOCK_POOL_ID);
    entityManager.clear();
    staleStockPool.reserve(1);

    assertThatThrownBy(() -> {
      repositoryAdapter.save(staleStockPool);
      jpaRepository.flush();
    }).isInstanceOf(ObjectOptimisticLockingFailureException.class);
  }

  @Test
  @DisplayName("資料庫應拒絕重複 SKU")
  void rejectsDuplicateSku() {
    insertRawStockPool(STOCK_POOL_ID, "SKU-1", 10, 0);

    assertThatThrownBy(() -> insertRawStockPool(UUID.randomUUID(), "SKU-1", 10, 0))
        .isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining("uq_stock_pools_sku");
  }

  @ParameterizedTest(name = "[{index}] onHand={0}, reserved={1}, constraint={2}")
  @MethodSource("invalidDatabaseQuantities")
  @DisplayName("資料庫應拒絕破壞 StockPool quantity invariants 的資料")
  void rejectsInvalidQuantities(
      int onHandQuantity,
      int reservedQuantity,
      String expectedConstraint
  ) {
    assertThatThrownBy(
        () -> insertRawStockPool(STOCK_POOL_ID, "SKU-1", onHandQuantity, reservedQuantity)
    ).isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining(expectedConstraint);
  }

  static Stream<Arguments> invalidDatabaseQuantities() {
    return Stream.of(
        Arguments.of(-1, 0, "ck_stock_pools_on_hand_non_negative"),
        Arguments.of(1, -1, "ck_stock_pools_reserved_non_negative"),
        Arguments.of(1, 2, "ck_stock_pools_reserved_not_above_on_hand")
    );
  }

  private StockPoolEntity persistStockPool(
      UUID id,
      String sku,
      int onHandQuantity,
      int reservedQuantity
  ) {
    StockPoolEntity saved = jpaRepository.saveAndFlush(
        new StockPoolEntity(id, sku, onHandQuantity, reservedQuantity, null)
    );
    entityManager.clear();
    return saved;
  }

  private void setOldUpdatedAt(UUID id) {
    jdbcTemplate.update(
        "UPDATE stock_pools SET updated_at = ? WHERE id = ?",
        Timestamp.from(OLD_UPDATED_AT),
        id
    );
    entityManager.clear();
  }

  private void insertRawStockPool(
      UUID id,
      String sku,
      int onHandQuantity,
      int reservedQuantity
  ) {
    jdbcTemplate.update("""
        INSERT INTO stock_pools (id, sku, on_hand_quantity, reserved_quantity)
        VALUES (?, ?, ?, ?)
        """, id, sku, onHandQuantity, reservedQuantity);
  }

  enum QuantityMutation {
    RESERVE(10, 3) {
      @Override
      void apply(StockPool stockPool) {
        stockPool.reserve(1);
      }
    },
    RELEASE(10, 1) {
      @Override
      void apply(StockPool stockPool) {
        stockPool.release(1);
      }
    },
    REPLENISH(11, 2) {
      @Override
      void apply(StockPool stockPool) {
        stockPool.replenish(1);
      }
    };

    private final int expectedOnHandQuantity;
    private final int expectedReservedQuantity;

    QuantityMutation(int expectedOnHandQuantity, int expectedReservedQuantity) {
      this.expectedOnHandQuantity = expectedOnHandQuantity;
      this.expectedReservedQuantity = expectedReservedQuantity;
    }

    abstract void apply(StockPool stockPool);
  }

  @TestConfiguration(proxyBeanMethods = false)
  @EnableJpaRepositories(basePackageClasses = JpaStockRepository.class)
  static class RepositoryConfiguration {
  }
}
