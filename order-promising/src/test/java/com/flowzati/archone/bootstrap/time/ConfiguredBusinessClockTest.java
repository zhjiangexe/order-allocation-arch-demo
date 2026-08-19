package com.flowzati.archone.bootstrap.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("營運日曆")
class ConfiguredBusinessClockTest {

  @Test
  @DisplayName("目前瞬間應直接來自注入的 Clock")
  void returnsTheInstantFromTheInjectedClock() {
    Instant now = Instant.parse("2026-07-21T23:00:00Z");

    assertThat(new ConfiguredBusinessClock(Clock.fixed(now, ZoneOffset.UTC), "Asia/Taipei").instant())
        .isEqualTo(now);
  }

  @Test
  @DisplayName("UTC 還停在昨天的那八小時內，今天應是營運時區的日期")
  void resolvesTodayInTheBusinessZoneNotInUtc() {
    // 台北 2026-07-22 早上 7 點；UTC 此刻是 2026-07-21 23:00，仍停在前一天。
    Clock clock = Clock.fixed(Instant.parse("2026-07-21T23:00:00Z"), ZoneOffset.UTC);

    ConfiguredBusinessClock calendar = new ConfiguredBusinessClock(clock, "Asia/Taipei");

    // 這正是效期比對會出錯的窗口：照 UTC 算的話，一批效期 07-21 的貨在台灣已經過期一天，
    // 系統卻還判定為可售，而且不會有任何錯誤浮現，貨就出去了。
    assertThat(calendar.today()).isEqualTo(LocalDate.of(2026, 7, 22));
    assertThat(LocalDate.now(clock)).isEqualTo(LocalDate.of(2026, 7, 21));
  }

  @Test
  @DisplayName("同一天之內的其他時刻應與營運時區的日期一致")
  void agreesWithTheBusinessZoneForTheRestOfTheDay() {
    Clock clock = Clock.fixed(Instant.parse("2026-07-22T06:00:00Z"), ZoneOffset.UTC);

    assertThat(new ConfiguredBusinessClock(clock, "Asia/Taipei").today())
        .isEqualTo(LocalDate.of(2026, 7, 22));
  }

  @Test
  @DisplayName("換一個營運時區就換一個日期——時區是設定，不是寫死的")
  void followsWhicheverBusinessZoneIsConfigured() {
    Clock clock = Clock.fixed(Instant.parse("2026-07-21T23:00:00Z"), ZoneOffset.UTC);

    assertThat(new ConfiguredBusinessClock(clock, "UTC").today()).isEqualTo(LocalDate.of(2026, 7, 21));
    assertThat(new ConfiguredBusinessClock(clock, "Asia/Taipei").today())
        .isEqualTo(LocalDate.of(2026, 7, 22));
  }
}
