package com.flowzati.archone.messaging.producer.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.messaging.api.MessageHeaders;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class JacksonMessageHeadersCodecTest {

    @Test
    void encodesDeterministicallyAndRestoresEscapedStringHeaders() {
        JacksonMessageHeadersCodec codec = new JacksonMessageHeadersCodec();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("traceparent", "00-abc-def-01");
        headers.put("correlation-id", "order-1");
        headers.put("escaped", "quote: \" and slash: \\");

        String encoded = codec.encode(headers);

        assertThat(encoded)
                .isEqualTo(codec.encode(Map.of(
                        "escaped", "quote: \" and slash: \\",
                        "correlation-id", "order-1",
                        "traceparent", "00-abc-def-01")))
                .contains("\\\"")
                .contains("\\\\");
        assertThat(codec.decode(encoded)).containsExactlyEntriesOf(new TreeMap<>(headers));
    }

    @Test
    void rejectsReservedPhysicalHeaders() {
        JacksonMessageHeadersCodec codec = new JacksonMessageHeadersCodec();

        assertThatThrownBy(() -> codec.encode(Map.of(MessageHeaders.MESSAGE_ID, "forged")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Reserved message header collision: message-id");
    }

    @Test
    void rejectsOversizedAndNonStringHeaderMaps() {
        JacksonMessageHeadersCodec countLimited = new JacksonMessageHeadersCodec(new ObjectMapper(), Set.of(), 1, 100);
        JacksonMessageHeadersCodec byteLimited = new JacksonMessageHeadersCodec(new ObjectMapper(), Set.of(), 10, 8);
        @SuppressWarnings({"rawtypes", "unchecked"})
        Map<String, String> invalidTypes = (Map) Map.of("attempt", 10);

        assertThatThrownBy(() -> countLimited.encode(Map.of("a", "1", "b", "2")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Message header count exceeds limit: 1");
        assertThatThrownBy(() -> byteLimited.encode(Map.of("long", "value")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Encoded message headers exceed byte limit: 8");
        assertThatThrownBy(() -> countLimited.encode(invalidTypes))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Message headers must contain only string values");
    }
}
