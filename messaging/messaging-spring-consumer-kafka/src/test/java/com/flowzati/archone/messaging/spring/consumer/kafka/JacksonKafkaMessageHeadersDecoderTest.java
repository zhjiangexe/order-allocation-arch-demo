package com.flowzati.archone.messaging.spring.consumer.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JacksonKafkaMessageHeadersDecoderTest {

  @Test
  void decodesAStringHeaderEnvelopeDeterministically() {
    JacksonKafkaMessageHeadersDecoder decoder =
        new JacksonKafkaMessageHeadersDecoder(new ObjectMapper());

    assertThat(decoder.decode("{\"traceparent\":\"00-abc\",\"correlation-id\":\"c-1\"}"))
        .containsExactlyEntriesOf(Map.of(
            "correlation-id", "c-1",
            "traceparent", "00-abc"));
  }

  @Test
  void rejectsNonStringAndOversizedHeaderEnvelopes() {
    JacksonKafkaMessageHeadersDecoder decoder =
        new JacksonKafkaMessageHeadersDecoder(new ObjectMapper(), 1, 32);

    assertThatThrownBy(() -> decoder.decode("{\"attempt\":1}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Message headers must contain only string values");
    assertThatThrownBy(() -> decoder.decode("{\"a\":\"1\",\"b\":\"2\"}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Message header count exceeds limit: 1");
  }
}
