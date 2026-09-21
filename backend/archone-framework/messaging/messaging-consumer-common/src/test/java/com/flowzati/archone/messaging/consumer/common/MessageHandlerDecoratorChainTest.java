package com.flowzati.archone.messaging.consumer.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.messaging.api.MessageBuilder;
import com.flowzati.archone.messaging.api.MessageContext;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class MessageHandlerDecoratorChainTest {

    @Test
    void canReturnDuplicateWithoutCallingTheTerminalHandler() {
        AtomicBoolean terminalCalled = new AtomicBoolean();
        MessageHandlerDecorator duplicate = new MessageHandlerDecorator() {
            @Override
            public int order() {
                return MessageHandlerDecoratorOrders.TRANSACTIONAL_IDEMPOTENCY;
            }

            @Override
            public MessageProcessingStatus handle(
                    MessageHandlerInvocation invocation, MessageHandlerDecoratorChain chain) {
                return MessageProcessingStatus.DUPLICATE;
            }
        };
        MessageHandlerDecoratorChain chain = MessageHandlerDecoratorChain.create(List.of(duplicate), invocation -> {
            terminalCalled.set(true);
            return MessageProcessingStatus.PROCESSED;
        });

        assertThat(chain.invokeNext(invocation())).isEqualTo(MessageProcessingStatus.DUPLICATE);
        assertThat(terminalCalled).isFalse();
    }

    @Test
    void preservesIgnoredUnhandledAsADistinctSuccessfulOutcome() {
        MessageHandlerDecoratorChain chain =
                MessageHandlerDecoratorChain.create(List.of(), invocation -> MessageProcessingStatus.IGNORED_UNHANDLED);

        assertThat(chain.invokeNext(invocation())).isEqualTo(MessageProcessingStatus.IGNORED_UNHANDLED);
    }

    @Test
    void rejectsDuplicateOrdersInsteadOfDependingOnDiscoveryOrder() {
        MessageHandlerDecorator first = passThrough(100);
        MessageHandlerDecorator second = passThrough(100);

        assertThatThrownBy(() -> MessageHandlerDecoratorChain.create(
                        List.of(first, second), invocation -> MessageProcessingStatus.PROCESSED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Duplicate message handler decorator order: 100");
    }

    private MessageHandlerDecorator passThrough(int order) {
        return new MessageHandlerDecorator() {
            @Override
            public int order() {
                return order;
            }

            @Override
            public MessageProcessingStatus handle(
                    MessageHandlerInvocation invocation, MessageHandlerDecoratorChain chain) {
                return chain.invokeNext(invocation);
            }
        };
    }

    private MessageHandlerInvocation invocation() {
        return new MessageHandlerInvocation(
                MessageBuilder.withPayload("{}")
                        .withId(UUID.randomUUID())
                        .withType("example.v1")
                        .withPartitionId("order-1")
                        .build(),
                new MessageContext("subscriber", "order-events", 1));
    }
}
