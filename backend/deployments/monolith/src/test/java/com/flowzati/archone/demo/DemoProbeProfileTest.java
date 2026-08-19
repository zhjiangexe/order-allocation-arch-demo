package com.flowzati.archone.demo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * 沒有 dev profile 時，探針端點必須是「不存在」而不是「存在但拒絕」——非 dev 環境不該有
 * 任何探針表面。
 */
@WebMvcTest(DemoConfigController.class)
class DemoProbeProfileTest {

    @Autowired
    private MockMvcTester mvc;

    @ParameterizedTest(name = "GET {0} 應回 404")
    @ValueSource(strings = {"/demo/config"})
    @DisplayName("未啟用 dev profile 時，唯讀探針端點不存在")
    void shouldNotRegisterReadOnlyProbesWithoutDevProfile(String path) {
        assertThat(mvc.get().uri(path)).hasStatus(404);
    }
}
