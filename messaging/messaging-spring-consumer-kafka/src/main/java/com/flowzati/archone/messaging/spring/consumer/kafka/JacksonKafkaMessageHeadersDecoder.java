package com.flowzati.archone.messaging.spring.consumer.kafka;

import com.flowzati.archone.messaging.api.MessageHeaders;
import com.flowzati.archone.messaging.api.MessageHeadersDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Bounded JSON decoder for the logical-header envelope relayed by Debezium. */
public final class JacksonKafkaMessageHeadersDecoder implements MessageHeadersDecoder {

  public static final int DEFAULT_MAX_HEADER_COUNT = 64;
  public static final int DEFAULT_MAX_ENCODED_BYTES = 16 * 1024;

  private final ObjectMapper objectMapper;
  private final int maxHeaderCount;
  private final int maxEncodedBytes;

  public JacksonKafkaMessageHeadersDecoder(ObjectMapper objectMapper) {
    this(objectMapper, DEFAULT_MAX_HEADER_COUNT, DEFAULT_MAX_ENCODED_BYTES);
  }

  public JacksonKafkaMessageHeadersDecoder(
      ObjectMapper objectMapper,
      int maxHeaderCount,
      int maxEncodedBytes
  ) {
    if (objectMapper == null) {
      throw new IllegalArgumentException("ObjectMapper is required");
    }
    if (maxHeaderCount < 0 || maxEncodedBytes < 2) {
      throw new IllegalArgumentException("Header decoder limits are invalid");
    }
    this.objectMapper = objectMapper;
    this.maxHeaderCount = maxHeaderCount;
    this.maxEncodedBytes = maxEncodedBytes;
  }

  @Override
  public Map<String, String> decode(String encodedHeaders) {
    if (encodedHeaders == null || encodedHeaders.isBlank()) {
      throw new IllegalArgumentException("Encoded message headers are required");
    }
    requireWithinSize(encodedHeaders);
    try {
      return Collections.unmodifiableMap(validateAndSort(objectMapper.readTree(encodedHeaders)));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Unable to deserialize message headers", exception);
    }
  }

  private Map<String, String> validateAndSort(JsonNode headers) {
    if (headers == null || !headers.isObject()) {
      throw new IllegalArgumentException("Message headers must be a JSON object");
    }
    if (headers.size() > maxHeaderCount) {
      throw new IllegalArgumentException(
          "Message header count exceeds limit: " + maxHeaderCount);
    }
    TreeMap<String, String> sorted = new TreeMap<>();
    Iterator<Map.Entry<String, JsonNode>> fields = headers.properties().iterator();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> entry = fields.next();
      if (!entry.getValue().isString()) {
        throw new IllegalArgumentException("Message headers must contain only string values");
      }
      String name = entry.getKey();
      String value = entry.getValue().stringValue();
      MessageHeaders.validate(name, value);
      sorted.put(name, value);
    }
    return sorted;
  }

  private void requireWithinSize(String encodedHeaders) {
    if (encodedHeaders.getBytes(StandardCharsets.UTF_8).length > maxEncodedBytes) {
      throw new IllegalArgumentException(
          "Encoded message headers exceed byte limit: " + maxEncodedBytes);
    }
  }
}
