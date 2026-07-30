package com.flowzati.archone.common.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CommonConfiguration {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  /**
   * 日期時間一律序列化為 ISO-8601 字串，不用 Jackson 預設的數字陣列。
   *
   * <p>這個 mapper 決定的是**跨服務的線上格式**——outbox 的 payload 與補貨探針發出的訊息都
   * 經過它。預設會把 {@code LocalDate} 寫成 {@code [2026,1,5]}，而那個陣列沒有任何自我描述：
   * 別的語言寫的消費端得先知道欄位順序是年月日才讀得懂，寫成月日年也不會有任何錯誤浮現。
   */
  @Bean
  ObjectMapper objectMapper() {
    return new ObjectMapper()
        .findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }
}
