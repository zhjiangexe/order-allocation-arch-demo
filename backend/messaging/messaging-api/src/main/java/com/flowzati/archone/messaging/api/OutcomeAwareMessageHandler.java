package com.flowzati.archone.messaging.api;

/**
 * Additive handler contract for protocols that distinguish handled from explicitly ignored input.
 *
 * <p>The basic {@link MessageHandler} remains the Tram-compatible public shape. Generic consumers
 * may detect this subtype to preserve a richer semantic outcome through their decorator chain.
 */
@FunctionalInterface
public interface OutcomeAwareMessageHandler extends MessageHandler {

    MessageHandlingStatus handleWithOutcome(Message message, MessageContext context);

    @Override
    default void handle(Message message, MessageContext context) {
        handleWithOutcome(message, context);
    }
}
