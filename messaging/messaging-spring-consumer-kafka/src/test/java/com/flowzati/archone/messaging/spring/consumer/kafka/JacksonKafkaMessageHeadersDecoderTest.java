package com.flowzati.archone.messaging.spring.consumer.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class JacksonKafkaMessageHeadersDecoderTest {

  @Test
  void decodesAStringHeaderEnvelopeDeterministically() {
    JacksonKafkaMessageHeadersDecoder decoder =
        new JacksonKafkaMessageHeadersDecoder(new ObjectMapper());

    assertThat(decoder.decode("{\"traceparent\":\"00-abc\",\"correlation-id\":\"c-1\"}"))
        .containsExactly(
            entry("correlation-id", "c-1"),
            entry("traceparent", "00-abc"));
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
