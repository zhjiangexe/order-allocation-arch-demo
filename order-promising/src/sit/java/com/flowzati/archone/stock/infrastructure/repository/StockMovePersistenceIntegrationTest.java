package com.flowzati.archone.stock.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.stock.domain.model.StockMove;
import com.flowzati.archone.stock.domain.model.WaitingAllocationScope;
import com.flowzati.archone.stock.infrastructure.repository.jpa.JpaStockMoveLineRepository;
import com.flowzati.archone.stock.infrastructure.repository.jpa.JpaStockMoveRepository;
import com.flowzati.archone.testsupport.MovementFixtures;
import com.flowzati.archone.testsupport.OrderFixtures;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest(properties = "spring.data.jpa.repositories.enabled=false", showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@ActiveProfiles("test")
@Import({
    PostgreSQLTestConfiguration.class,
    StockMoveRepositoryImpl.class,
    StockMovePersistenceIntegrationTest.RepositoryConfiguration.class
})
@DisplayName("StockMove PostgreSQL persistence adapter")
class StockMovePersistenceIntegrationTest {

  private static final String MATCHING_SKU = "SKU-1";
  private static final String OTHER_SKU = "SKU-2";
  private static final Instant CREATED_AT = Instant.parse("2026-08-03T01:00:00Z");

  @Autowired
  private StockMoveRepositoryImpl repository;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void seedCatalog() {
    OrderFixtures.seedCatalog(
        jdbcTemplate, OrderFixtures.OWNER_ID, MATCHING_SKU, OTHER_SKU);
  }

  @Test
  @DisplayName("待配佇列只收訂單 outbound picking，並保住 FIFO 與整單搬運")
  void shouldReturnOnlyWholeOrderDrivenPickingGroupsInFifoOrder() {
    UUID firstOrder = uuid(10);
    UUID secondOrder = uuid(20);
    UUID firstPicking = uuid(11);
    UUID secondPicking = uuid(21);
    UUID inboundPicking = uuid(30);

    insertOrder(firstOrder, "EXT-FIRST");
    insertOrder(secondOrder, "EXT-SECOND");
    insertPicking(firstPicking, firstOrder, MovementFixtures.OUTBOUND_TYPE_ID,
        OrderFixtures.LOCATION_ID, MovementFixtures.CUSTOMERS_LOCATION_ID);
    insertPicking(secondPicking, secondOrder, MovementFixtures.OUTBOUND_TYPE_ID,
        OrderFixtures.LOCATION_ID, MovementFixtures.CUSTOMERS_LOCATION_ID);
    insertPicking(inboundPicking, null, MovementFixtures.INBOUND_TYPE_ID,
        MovementFixtures.SUPPLIERS_LOCATION_ID, OrderFixtures.LOCATION_ID);

    UUID firstMatchingLine = uuid(12);
    UUID firstOtherLine = uuid(13);
    UUID secondMatchingLine = uuid(22);
    insertOrderLine(firstMatchingLine, firstOrder, 1, MATCHING_SKU);
    insertOrderLine(firstOtherLine, firstOrder, 2, OTHER_SKU);
    insertOrderLine(secondMatchingLine, secondOrder, 1, MATCHING_SKU);

    insertMove(uuid(14), firstPicking, MATCHING_SKU, OrderFixtures.LOCATION_ID,
        MovementFixtures.CUSTOMERS_LOCATION_ID, firstMatchingLine);
    insertMove(uuid(15), firstPicking, OTHER_SKU, OrderFixtures.LOCATION_ID,
        MovementFixtures.CUSTOMERS_LOCATION_ID, firstOtherLine);
    insertMove(uuid(23), secondPicking, MATCHING_SKU, OrderFixtures.LOCATION_ID,
        MovementFixtures.CUSTOMERS_LOCATION_ID, secondMatchingLine);
    insertMove(uuid(31), inboundPicking, MATCHING_SKU, MovementFixtures.SUPPLIERS_LOCATION_ID,
        OrderFixtures.LOCATION_ID, null);
    insertMove(uuid(32), null, MATCHING_SKU, OrderFixtures.LOCATION_ID,
        MovementFixtures.CUSTOMERS_LOCATION_ID, null);

    List<StockMove> waiting = repository.findWaitingInFifoOrder(
        OrderFixtures.OWNER_ID, OrderFixtures.LOCATION_ID, MATCHING_SKU, 2);

    assertThat(waiting).extracting(StockMove::getPickingId)
        .containsExactly(firstPicking, firstPicking, secondPicking);
    assertThat(waiting).extracting(StockMove::getSkuCode)
        .containsExactly(MATCHING_SKU, OTHER_SKU, MATCHING_SKU);
  }

  @Test
  @DisplayName("scheduler scope 只列出仍等待的訂單 outbound owner/location/SKU")
  void shouldReturnDistinctWaitingAllocationScopes() {
    jdbcTemplate.update("""
        INSERT INTO stock_pools
            (id, owner_id, location_id, sku_code, in_date, expiry_date,
             on_hand_quantity, reserved_quantity, version, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, 10, 0, 0, now()),
               (?, ?, ?, ?, ?, ?, 10, 0, 0, now())
        """,
        uuid(60), OrderFixtures.OWNER_ID, OrderFixtures.LOCATION_ID, MATCHING_SKU,
        Date.valueOf(LocalDate.of(2026, 8, 1)), Date.valueOf(LocalDate.of(2026, 12, 31)),
        uuid(61), OrderFixtures.OWNER_ID, OrderFixtures.LOCATION_ID, OTHER_SKU,
        Date.valueOf(LocalDate.of(2026, 8, 1)), Date.valueOf(LocalDate.of(2026, 12, 31)));
    UUID order = uuid(40);
    UUID outboundPicking = uuid(41);
    UUID inboundPicking = uuid(50);
    UUID matchingLine = uuid(42);
    UUID otherLine = uuid(43);
    insertOrder(order, "EXT-SCOPES");
    insertPicking(outboundPicking, order, MovementFixtures.OUTBOUND_TYPE_ID,
        OrderFixtures.LOCATION_ID, MovementFixtures.CUSTOMERS_LOCATION_ID);
    insertPicking(inboundPicking, null, MovementFixtures.INBOUND_TYPE_ID,
        MovementFixtures.SUPPLIERS_LOCATION_ID, OrderFixtures.LOCATION_ID);
    insertOrderLine(matchingLine, order, 1, MATCHING_SKU);
    insertOrderLine(otherLine, order, 2, OTHER_SKU);
    insertMove(uuid(44), outboundPicking, MATCHING_SKU, OrderFixtures.LOCATION_ID,
        MovementFixtures.CUSTOMERS_LOCATION_ID, matchingLine);
    insertMove(uuid(45), outboundPicking, OTHER_SKU, OrderFixtures.LOCATION_ID,
        MovementFixtures.CUSTOMERS_LOCATION_ID, otherLine);
    insertMove(uuid(51), inboundPicking, MATCHING_SKU, MovementFixtures.SUPPLIERS_LOCATION_ID,
        OrderFixtures.LOCATION_ID, null);
    insertMove(uuid(52), null, MATCHING_SKU, OrderFixtures.LOCATION_ID,
        MovementFixtures.CUSTOMERS_LOCATION_ID, null);

    assertThat(repository.findAllocatableWaitingScopes(LocalDate.of(2026, 8, 4), 10))
        .containsExactly(
        new WaitingAllocationScope(
            OrderFixtures.OWNER_ID, OrderFixtures.FACILITY_ID,
            OrderFixtures.LOCATION_ID, MATCHING_SKU),
        new WaitingAllocationScope(
            OrderFixtures.OWNER_ID, OrderFixtures.FACILITY_ID,
            OrderFixtures.LOCATION_ID, OTHER_SKU));
  }

  private void insertOrder(UUID orderId, String externalOrderNo) {
    jdbcTemplate.update("""
        INSERT INTO orders
            (id, owner_id, external_order_no, ship_to_zone, ship_to_address,
             promised_delivery_date, facility_id, status, received_at, version)
        VALUES (?, ?, ?, '100', 'addr', ?, ?, 'PENDING', ?, 0)
        """,
        orderId,
        OrderFixtures.OWNER_ID,
        externalOrderNo,
        Date.valueOf(LocalDate.of(2026, 12, 31)),
        OrderFixtures.FACILITY_ID,
        Timestamp.from(CREATED_AT));
  }

  private void insertOrderLine(UUID lineId, UUID orderId, int lineNo, String skuCode) {
    jdbcTemplate.update("""
        INSERT INTO order_lines (id, order_id, line_no, owner_id, sku_code, quantity)
        VALUES (?, ?, ?, ?, ?, 1)
        """, lineId, orderId, lineNo, OrderFixtures.OWNER_ID, skuCode);
  }

  private void insertPicking(
      UUID pickingId,
      UUID orderId,
      UUID pickingTypeId,
      UUID fromLocationId,
      UUID toLocationId
  ) {
    jdbcTemplate.update("""
        INSERT INTO stock_pickings
            (id, picking_type_id, owner_id, order_id, from_location_id, to_location_id)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        pickingId,
        pickingTypeId,
        OrderFixtures.OWNER_ID,
        orderId,
        fromLocationId,
        toLocationId);
  }

  private void insertMove(
      UUID moveId,
      UUID pickingId,
      String skuCode,
      UUID fromLocationId,
      UUID toLocationId,
      UUID orderLineId
  ) {
    jdbcTemplate.update("""
        INSERT INTO stock_moves
            (id, picking_id, owner_id, sku_code, from_location_id, to_location_id,
             order_line_id, demand_quantity, state, created_at, version)
        VALUES (?, ?, ?, ?, ?, ?, ?, 1, 'CONFIRMED', ?, 0)
        """,
        moveId,
        pickingId,
        OrderFixtures.OWNER_ID,
        skuCode,
        fromLocationId,
        toLocationId,
        orderLineId,
        Timestamp.from(CREATED_AT));
  }

  private static UUID uuid(int seed) {
    return UUID.fromString(String.format("00000000-0000-7000-8000-%012d", seed));
  }

  @TestConfiguration(proxyBeanMethods = false)
  @EnableJpaRepositories(
      basePackageClasses = {JpaStockMoveRepository.class, JpaStockMoveLineRepository.class})
  static class RepositoryConfiguration {
  }
}
