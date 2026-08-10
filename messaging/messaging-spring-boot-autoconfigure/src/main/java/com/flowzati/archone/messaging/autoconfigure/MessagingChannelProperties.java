package com.flowzati.archone.messaging.autoconfigure;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Logical-to-physical destination mappings shared by producers and consumers. */
@ConfigurationProperties("archone.messaging.channels")
public class MessagingChannelProperties {

  private Map<String, String> mappings = new LinkedHashMap<>();

  public Map<String, String> getMappings() {
    return mappings;
  }

  public void setMappings(Map<String, String> mappings) {
    this.mappings = mappings == null ? new LinkedHashMap<>() : new LinkedHashMap<>(mappings);
  }
}
