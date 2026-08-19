package com.flowzati.archone.messaging.producer.jdbc;

import com.flowzati.archone.messaging.api.MessageHeadersDecoder;
import java.util.Map;

/** Encodes logical message headers into the single Outbox CDC headers column. */
public interface MessageHeadersCodec extends MessageHeadersDecoder {

    String encode(Map<String, String> headers);

    @Override
    Map<String, String> decode(String encodedHeaders);
}
