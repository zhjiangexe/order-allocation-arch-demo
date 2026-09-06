package com.flowzati.archone.bootstrap.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BusinessClockConfigurationTest {

    @Test
    @DisplayName("系統 Clock 應統一使用 UTC")
    void systemClockUsesUtc() {
        assertThat(new BusinessClockConfiguration().clock().getZone()).isEqualTo(ZoneOffset.UTC);
    }
}
