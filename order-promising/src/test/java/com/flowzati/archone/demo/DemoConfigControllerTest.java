package com.flowzati.archone.demo;

import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;

class DemoConfigControllerTest {

  @WebMvcTest(DemoConfigController.class)
  @ActiveProfiles("dev")
  @TestPropertySource(properties = "archone.allocation.partition-key-strategy=stock")
  static class SkuStrategy {

    @Autowired
    private MockMvcTester mvc;

    @org.junit.jupiter.api.Test
    @DisplayName("以 sku 策略啟動時應回報 sku")
    void shouldReportSkuStrategy() {
      assertThat(mvc.get().uri("/demo/config"))
          .hasStatus(200)
          .bodyJson().extractingPath("$.partitionKeyStrategy").isEqualTo("stock");
    }
  }

  @WebMvcTest(DemoConfigController.class)
  @ActiveProfiles("dev")
  static class DefaultStrategy {

    @Autowired
    private MockMvcTester mvc;

    @org.junit.jupiter.api.Test
    @DisplayName("未設定時應回報預設的 order-id 策略")
    void shouldReportDefaultStrategy() {
      assertThat(mvc.get().uri("/demo/config"))
          .hasStatus(200)
          .bodyJson().extractingPath("$.partitionKeyStrategy").isEqualTo("order-id");
    }
  }
}
