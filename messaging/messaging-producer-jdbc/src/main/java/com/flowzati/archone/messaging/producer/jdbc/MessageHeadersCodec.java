package com.flowzati.archone.messaging.producer.jdbc;

import java.util.Map;

/** Encodes logical message headers into the single Outbox CDC headers column. */
public interface MessageHeadersCodec {

  String encode(Map<String, String> headers);

  Map<String, String> decode(String encodedHeaders);
}
