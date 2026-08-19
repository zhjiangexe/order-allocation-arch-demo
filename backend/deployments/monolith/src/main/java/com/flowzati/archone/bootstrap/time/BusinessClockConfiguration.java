package com.flowzati.archone.bootstrap.time;

import com.flowzati.archone.foundation.time.BusinessClock;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 由 application bootstrap 決定系統時間與營運日曆的組裝方式。
 *
 * <p>{@link Clock} 統一使用 UTC 表示時間軸上的瞬間；{@link BusinessClock} 再依營運時區將瞬間
 * 轉成「今天是幾號」。這些都是 deployable 的 runtime policy，不屬於任一 bounded context。
 */
@Configuration(proxyBeanMethods = false)
public class BusinessClockConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    BusinessClock configuredBusinessClock(
            Clock clock, @Value("${archone.business-zone:Asia/Taipei}") String businessZone) {
        return new ConfiguredBusinessClock(clock, businessZone);
    }
}
