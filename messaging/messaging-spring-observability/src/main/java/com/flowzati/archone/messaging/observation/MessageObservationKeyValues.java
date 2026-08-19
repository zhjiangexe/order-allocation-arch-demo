package com.flowzati.archone.messaging.observation;

import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageHeaders;
import io.micrometer.common.KeyValues;

final class MessageObservationKeyValues {

    private MessageObservationKeyValues() {}

    static String messageType(Message message) {
        return message.header(MessageHeaders.MESSAGE_TYPE).orElse(MessagingObservationTags.UNKNOWN);
    }

    static String exceptionType(Throwable failure) {
        return failure == null
                ? MessagingObservationTags.NO_EXCEPTION
                : failure.getClass().getName();
    }

    static KeyValues traceOnlyIdentifiers(Message message) {
        KeyValues keyValues = KeyValues.empty();
        keyValues = addHeader(keyValues, message, MessageHeaders.MESSAGE_ID, MessagingObservationTags.MESSAGE_ID);
        keyValues = addHeader(keyValues, message, MessageHeaders.PARTITION_ID, MessagingObservationTags.PARTITION_ID);
        return addHeader(keyValues, message, MessageHeaders.CORRELATION_ID, MessagingObservationTags.CORRELATION_ID);
    }

    private static KeyValues addHeader(KeyValues keyValues, Message message, String header, String observationKey) {
        return message.header(header)
                .map(value -> keyValues.and(observationKey, value))
                .orElse(keyValues);
    }
}
