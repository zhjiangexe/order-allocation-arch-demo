package com.flowzati.archone.messaging.consumer.common;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable, thread-safe ordered decorator chain. */
public final class MessageHandlerDecoratorChain {

    private final List<MessageHandlerDecorator> decorators;
    private final OutcomeMessageHandler terminalHandler;
    private final int index;

    private MessageHandlerDecoratorChain(
            List<MessageHandlerDecorator> decorators, OutcomeMessageHandler terminalHandler, int index) {
        this.decorators = decorators;
        this.terminalHandler = terminalHandler;
        this.index = index;
    }

    public static MessageHandlerDecoratorChain create(
            List<MessageHandlerDecorator> decorators, OutcomeMessageHandler terminalHandler) {
        if (decorators == null || decorators.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Message handler decorators are required");
        }
        Objects.requireNonNull(terminalHandler, "Terminal message handler is required");
        List<MessageHandlerDecorator> ordered = decorators.stream()
                .sorted(Comparator.comparingInt(MessageHandlerDecorator::order))
                .toList();
        Set<Integer> seenOrders = new HashSet<>();
        for (MessageHandlerDecorator decorator : ordered) {
            if (!seenOrders.add(decorator.order())) {
                throw new IllegalArgumentException("Duplicate message handler decorator order: " + decorator.order());
            }
        }
        return new MessageHandlerDecoratorChain(ordered, terminalHandler, 0);
    }

    public ProcessingOutcome invokeNext(MessageHandlerInvocation invocation) {
        Objects.requireNonNull(invocation, "Message handler invocation is required");
        if (index == decorators.size()) {
            ProcessingOutcome outcome = terminalHandler.handle(invocation);
            return Objects.requireNonNull(outcome, "Terminal message handler returned null");
        }
        MessageHandlerDecorator decorator = decorators.get(index);
        MessageHandlerDecoratorChain remainder =
                new MessageHandlerDecoratorChain(decorators, terminalHandler, index + 1);
        ProcessingOutcome outcome = decorator.handle(invocation, remainder);
        return Objects.requireNonNull(
                outcome,
                () -> "Message handler decorator returned null: "
                        + decorator.getClass().getName());
    }
}
