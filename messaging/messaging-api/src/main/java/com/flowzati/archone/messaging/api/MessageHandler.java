package com.flowzati.archone.messaging.api;

/** Application-facing callback for one generic inbound message. */
@FunctionalInterface
public interface MessageHandler {

    void handle(Message message, MessageContext context);
}
