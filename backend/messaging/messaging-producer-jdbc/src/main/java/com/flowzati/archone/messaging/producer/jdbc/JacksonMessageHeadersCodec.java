package com.flowzati.archone.messaging.producer.jdbc;

import com.flowzati.archone.messaging.api.MessageHeaders;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationFeature;

/** Deterministic JSON codec with bounded size and reserved-key protection. */
public final class JacksonMessageHeadersCodec implements MessageHeadersCodec {

    public static final int DEFAULT_MAX_HEADER_COUNT = 64;
    public static final int DEFAULT_MAX_ENCODED_BYTES = 16 * 1024;

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final ObjectWriter objectWriter;
    private final Set<String> reservedHeaders;
    private final int maxHeaderCount;
    private final int maxEncodedBytes;

    public JacksonMessageHeadersCodec() {
        this(new ObjectMapper(), OutboxPhysicalHeaders.ALL, DEFAULT_MAX_HEADER_COUNT, DEFAULT_MAX_ENCODED_BYTES);
    }

    public JacksonMessageHeadersCodec(
            ObjectMapper objectMapper, Set<String> reservedHeaders, int maxHeaderCount, int maxEncodedBytes) {
        if (objectMapper == null || reservedHeaders == null) {
            throw new IllegalArgumentException("Header codec dependencies are required");
        }
        if (maxHeaderCount < 0 || maxEncodedBytes < 2) {
            throw new IllegalArgumentException("Header codec limits are invalid");
        }
        this.objectMapper = objectMapper;
        this.objectWriter = objectMapper.writer(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.reservedHeaders = Set.copyOf(reservedHeaders);
        this.maxHeaderCount = maxHeaderCount;
        this.maxEncodedBytes = maxEncodedBytes;
    }

    @Override
    public String encode(Map<String, String> headers) {
        Map<String, String> validated = validateAndSort(headers);
        try {
            String encoded = objectWriter.writeValueAsString(validated);
            requireWithinSize(encoded);
            return encoded;
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Unable to serialize message headers", exception);
        }
    }

    @Override
    public Map<String, String> decode(String encodedHeaders) {
        if (encodedHeaders == null || encodedHeaders.isBlank()) {
            throw new IllegalArgumentException("Encoded message headers are required");
        }
        requireWithinSize(encodedHeaders);
        try {
            Map<String, String> decoded = objectMapper.readValue(encodedHeaders, STRING_MAP);
            return Collections.unmodifiableMap(validateAndSort(decoded));
        } catch (JacksonException | ClassCastException exception) {
            throw new IllegalArgumentException("Unable to deserialize message headers", exception);
        }
    }

    private Map<String, String> validateAndSort(Map<String, String> headers) {
        if (headers == null) {
            throw new IllegalArgumentException("Message headers are required");
        }
        if (headers.size() > maxHeaderCount) {
            throw new IllegalArgumentException("Message header count exceeds limit: " + maxHeaderCount);
        }

        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) headers).entrySet()) {
            if (!(entry.getKey() instanceof String name) || !(entry.getValue() instanceof String value)) {
                throw new IllegalArgumentException("Message headers must contain only string values");
            }
            MessageHeaders.validate(name, value);
            if (reservedHeaders.contains(name)) {
                throw new IllegalArgumentException("Reserved message header collision: " + name);
            }
            sorted.put(name, value);
        }
        return sorted;
    }

    private void requireWithinSize(String encoded) {
        int size = encoded.getBytes(StandardCharsets.UTF_8).length;
        if (size > maxEncodedBytes) {
            throw new IllegalArgumentException("Encoded message headers exceed byte limit: " + maxEncodedBytes);
        }
    }
}
