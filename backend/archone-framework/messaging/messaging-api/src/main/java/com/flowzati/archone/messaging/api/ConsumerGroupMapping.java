package com.flowzati.archone.messaging.api;

/** Maps a stable Inbox subscriber identity to its broker consumer-group identity. */
@FunctionalInterface
public interface ConsumerGroupMapping {

    String transform(String subscriberId);
}
