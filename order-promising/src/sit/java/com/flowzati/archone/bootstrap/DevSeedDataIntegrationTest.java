package com.flowzati.archone.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.ArchoneApplication;
import com.flowzati.archone.allocation.domain.model.ReservationStatus;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.allocation.domain.repository.StockReservationRepository;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import org.junit.jupiter.api.AfterEach;
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
  private StockPoolRepository stockPoolRepository;

  @Autowired
  private OrderRepository orderRepository;

  @Autowired
  private StockReservationRepository stockReservationRepository;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @AfterEach
  void clearDatabase() {
    jdbcTemplate.execute("DELETE FROM stock_reservations");
    jdbcTemplate.execute("DELETE FROM orders");
    jdbcTemplate.execute("DELETE FROM stock_pools");
  }

  @Test
  void shouldCreateConsistentDevSeedDataWithoutDuplicatesOnRepeatRun() throws Exception {
    assertThat(stockPoolRepository.findBySku(DevSeedDataInitializer.AVAILABLE_SKU))
        .hasValueSatisfying(pool -> {
          assertThat(pool.getOnHandQuantity()).isEqualTo(10);
          assertThat(pool.getReservedQuantity()).isZero();
        });
    assertThat(stockPoolRepository.findBySku(DevSeedDataInitializer.EMPTY_SKU))
        .hasValueSatisfying(pool -> {
          assertThat(pool.getOnHandQuantity()).isZero();
          assertThat(pool.getReservedQuantity()).isZero();
        });
    assertThat(stockPoolRepository.findBySku(DevSeedDataInitializer.PARTIALLY_RESERVED_SKU))
        .hasValueSatisfying(pool -> {
          assertThat(pool.getOnHandQuantity()).isEqualTo(20);
          assertThat(pool.getReservedQuantity()).isEqualTo(5);
        });
    assertThat(orderRepository.findById(DevSeedDataInitializer.PARTIALLY_RESERVED_ORDER_ID))
        .hasValueSatisfying(order -> assertThat(order.getStatus()).isEqualTo(OrderStatus.ALLOCATED));
    assertThat(stockReservationRepository.findActiveByOrderId(
        DevSeedDataInitializer.PARTIALLY_RESERVED_ORDER_ID))
        .hasValueSatisfying(reservation -> {
          assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.ACTIVE);
          assertThat(reservation.getQuantity()).isEqualTo(5);
          assertThat(reservation.getStockPoolId())
              .isEqualTo(DevSeedDataInitializer.PARTIALLY_RESERVED_STOCK_POOL_ID);
        });

    initializer.run(null);

    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stock_pools", Integer.class))
        .isEqualTo(3);
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders", Integer.class))
        .isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stock_reservations", Integer.class))
        .isEqualTo(1);
  }
}
