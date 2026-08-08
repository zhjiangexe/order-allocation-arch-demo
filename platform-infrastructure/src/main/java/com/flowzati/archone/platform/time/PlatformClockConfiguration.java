package com.flowzati.archone.platform.time;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PlatformClockConfiguration {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
