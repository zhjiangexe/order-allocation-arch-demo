package com.flowzati.archone.messaging.testsupport;

import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageBuilder;
import java.time.Instant;
import java.util.UUID;

/** Deterministic generic messages shared by implementation contract tests. */
public final class MessageFixtures {

    public static final UUID MESSAGE_ID = UUID.fromString("00000000-0000-0000-0000-000000000123");
    public static final Instant MESSAGE_DATE = Instant.parse("2026-08-09T10:00:00Z");

    private MessageFixtures() {}

    public static Message message() {
        return MessageBuilder.withPayload("{\"fixture\":true}")
                .withId(MESSAGE_ID)
                .withType("test-message.v1")
                .withPartitionId("aggregate-1")
                .withMessageDate(MESSAGE_DATE)
                .build();
    }
}
