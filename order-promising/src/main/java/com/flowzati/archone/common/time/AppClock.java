package com.flowzati.archone.common.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 「今天是幾號」——依營運日曆的時區，把時間軸上的瞬間切成一天。
 *
 * <p><b>這一步需要時區，而且不能用預設的。</b>{@code Clock} 是 UTC 的，因為所有 {@code Instant}
 * 都該是 UTC；但 {@code LocalDate.now(utcClock)} 拿到的是 UTC 的日期，台北時間每天 00:00–08:00
 * 之間它還停在昨天。效期比對踩到那八小時，就會把已經過期一天的貨判成未過期——而且不會有任何
 * 錯誤浮現，只是貨出去了。
 *
 * <p>收成一個具名型別而不是在三處各寫一次 {@code LocalDate.now(clock.withZone(zone))}：規則只有
 * 一份，就不會有人漏掉 {@code withZone} 而回到原本的錯。
 *
 * <p><b>已知的簡化：時區是全域一個。</b>正確的模型是時區屬於倉庫——東京倉的貨照東京的日曆過期
 * ——該放在 {@code fulfillment_nodes.time_zone}。本專案的倉全在台灣，現在做等於為想像中的需求
 * 先設計；已記在 roadmap 的「已識別未排程」。
 */
@Component
public class AppClock {

  private final Clock clock;

  public AppClock(
      Clock clock,
      @Value("${archone.business-zone:Asia/Taipei}") String businessZone
  ) {
    this.clock = clock.withZone(ZoneId.of(businessZone));
  }

  public LocalDate today() {
    return LocalDate.now(clock);
  }

  public Instant instant() {
    return clock.instant();
  }
}
