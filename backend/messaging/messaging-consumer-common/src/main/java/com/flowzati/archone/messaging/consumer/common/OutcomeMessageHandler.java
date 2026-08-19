package com.flowzati.archone.messaging.consumer.common;

/** Internal semantic terminal used by the ordered decorator chain. */
@FunctionalInterface
public interface OutcomeMessageHandler {

    ProcessingOutcome handle(MessageHandlerInvocation invocation);
}
