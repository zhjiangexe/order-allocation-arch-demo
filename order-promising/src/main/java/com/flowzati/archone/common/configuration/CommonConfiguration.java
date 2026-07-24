package com.flowzati.archone.common.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CommonConfiguration {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  ObjectMapper objectMapper() {
    return new ObjectMapper().findAndRegisterModules();
  }
}
