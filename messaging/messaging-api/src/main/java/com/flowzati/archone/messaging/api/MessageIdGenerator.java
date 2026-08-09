package com.flowzati.archone.messaging.api;

import java.util.UUID;

/** Port used by generic producer orchestration when a caller did not supply a message ID. */
@FunctionalInterface
public interface MessageIdGenerator {

  UUID generate();
}
