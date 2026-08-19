package com.flowzati.archone.messaging.consumer.common;

/** One explicitly ordered layer around semantic message handling. */
public interface MessageHandlerDecorator {

    int order();

    ProcessingOutcome handle(MessageHandlerInvocation invocation, MessageHandlerDecoratorChain chain);
}
