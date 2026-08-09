package com.flowzati.archone.messaging.api;

import java.util.Map;

/** Decodes a transport-provided serialized logical-header envelope. */
@FunctionalInterface
public interface MessageHeadersDecoder {

  Map<String, String> decode(String encodedHeaders);
}
