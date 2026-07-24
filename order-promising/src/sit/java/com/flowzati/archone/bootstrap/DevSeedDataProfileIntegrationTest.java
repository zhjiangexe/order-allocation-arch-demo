package com.flowzati.archone.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.ArchoneApplication;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    classes = ArchoneApplication.class,
    properties = "spring.kafka.listener.auto-startup=false",
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(PostgreSQLTestConfiguration.class)
class DevSeedDataProfileIntegrationTest {

  @Autowired
  private ApplicationContext applicationContext;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @AfterEach
  void clearDatabase() {
    jdbcTemplate.execute("DELETE FROM stock_reservations");
    jdbcTemplate.execute("DELETE FROM orders");
    jdbcTemplate.execute("DELETE FROM stock_pools");
  }

  @Test
  @DisplayName("非 dev profile 不應載入示範資料")
  void shouldNotLoadDevSeedDataOutsideDevProfile() {
    assertThat(applicationContext.getBeansOfType(DevSeedDataInitializer.class)).isEmpty();
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stock_pools", Integer.class))
        .isZero();
  }
}
