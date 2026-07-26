package com.flowzati.archone.demo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 沒有 dev profile 時，探針端點必須是「不存在」而不是「存在但拒絕」——非 dev 環境不該有
 * 任何探針表面。
 */
@WebMvcTest({ReplenishmentProbeController.class, DemoConfigController.class})
class DemoProbeProfileTest {

  @Autowired
  private MockMvcTester mvc;

  @ParameterizedTest(name = "GET {0} 應回 404")
  @ValueSource(strings = {"/demo/config"})
  @DisplayName("未啟用 dev profile 時，唯讀探針端點不存在")
  void shouldNotRegisterReadOnlyProbesWithoutDevProfile(String path) {
    assertThat(mvc.get().uri(path)).hasStatus(404);
  }

  @org.junit.jupiter.api.Test
  @DisplayName("未啟用 dev profile 時，補貨探針不存在")
  void shouldNotRegisterReplenishmentProbeWithoutDevProfile() {
    assertThat(mvc.post().uri("/demo/replenish")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"sku\":\"HOT-SKU\",\"quantity\":500}"))
        .hasStatus(404);
  }
}
