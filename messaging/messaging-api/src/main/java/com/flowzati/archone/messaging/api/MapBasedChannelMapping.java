package com.flowzati.archone.messaging.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Immutable explicit channel mapping with identity fallback for unmapped channels. */
public final class MapBasedChannelMapping implements ChannelMapping {

  private final Map<String, String> mappings;

  public MapBasedChannelMapping(Map<String, String> mappings) {
    if (mappings == null) {
      throw new IllegalArgumentException("Channel mappings are required");
    }

    LinkedHashMap<String, String> validated = new LinkedHashMap<>();
    mappings.forEach((logical, physical) -> {
      if (isBlank(logical) || isBlank(physical)) {
        throw new IllegalArgumentException("Logical and physical channels are required");
      }
      validated.put(logical, physical);
    });
    Set<String> duplicates = validated.values().stream()
        .filter(value -> java.util.Collections.frequency(validated.values(), value) > 1)
        .collect(Collectors.toSet());
    if (!duplicates.isEmpty()) {
      throw new IllegalArgumentException(
          "Multiple logical channels map to the same destination: " + duplicates);
    }
    this.mappings = Map.copyOf(validated);
  }

  @Override
  public String transform(String logicalChannel) {
    if (isBlank(logicalChannel)) {
      throw new IllegalArgumentException("Logical channel is required");
    }
    return mappings.getOrDefault(logicalChannel, logicalChannel);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
