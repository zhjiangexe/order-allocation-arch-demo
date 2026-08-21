package com.flowzati.archone.messaging.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.messaging.api.MapBasedChannelMapping;
import com.flowzati.archone.messaging.api.MessageContext;
import com.flowzati.archone.messaging.api.MessageSubscriptionOptions;
import com.flowzati.archone.messaging.consumer.common.MessageConsumerImpl;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerDecorator;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerDecoratorChain;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerInvocation;
import com.flowzati.archone.messaging.consumer.common.MessageProcessingStatus;
import com.flowzati.archone.messaging.producer.common.MessageProducerImpl;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MessagingCommonContractTest {

    @Test
    void producerImplementationReceivesTheMappedDestinationAndNormalizedEnvelope() {
        RecordingMessageProducerImplementation implementation = new RecordingMessageProducerImplementation();
        MessageProducerImpl producer = new MessageProducerImpl(
                implementation,
                new MapBasedChannelMapping(Map.of("orders", "prod.orders")),
                List.of(),
                () -> UUID.fromString("00000000-0000-0000-0000-000000000999"),
                Clock.fixed(MessageFixtures.MESSAGE_DATE, ZoneOffset.UTC));

        producer.send("orders", MessageFixtures.message());

        assertThat(implementation.sentMessages()).singleElement().satisfies(sent -> {
            assertThat(sent.destination()).isEqualTo("prod.orders");
            assertThat(sent.message().id()).isEqualTo(MessageFixtures.MESSAGE_ID);
        });
    }

    @Test
    void consumerImplementationReceivesMappedChannelsAndKeepsIdentitiesSeparate() {
        ControllableMessageConsumerImplementation implementation = new ControllableMessageConsumerImplementation();
        MessageConsumerImpl consumer = new MessageConsumerImpl(
                implementation, new MapBasedChannelMapping(Map.of("orders", "prod.orders")), List.of());
        AtomicReference<MessageContext> handled = new AtomicReference<>();

        consumer.subscribe(
                "inbox-scope",
                Set.of("orders"),
                (message, context) -> handled.set(context),
                MessageSubscriptionOptions.withConsumerGroupId("broker-group"));
        implementation.emit("inbox-scope", "prod.orders", MessageFixtures.message(), 1);

        assertThat(implementation.subscriptions()).singleElement().satisfies(subscription -> {
            assertThat(subscription.subscriberId()).isEqualTo("inbox-scope");
            assertThat(subscription.consumerGroupId()).isEqualTo("broker-group");
        });
        assertThat(handled.get()).isEqualTo(new MessageContext("inbox-scope", "orders", 1));
    }

    @Test
    void decoratorOrderingIsNumericAndNotRegistrationOrder() {
        List<Integer> calls = new ArrayList<>();
        MessageHandlerDecorator outer = decorator(100, calls);
        MessageHandlerDecorator inner = decorator(200, calls);
        MessageHandlerDecoratorChain chain = MessageHandlerDecoratorChain.create(List.of(inner, outer), invocation -> {
            calls.add(999);
            return MessageProcessingStatus.PROCESSED;
        });

        chain.invokeNext(
                new MessageHandlerInvocation(MessageFixtures.message(), new MessageContext("subscriber", "orders", 1)));

        assertThat(calls).containsExactly(100, 200, 999, -200, -100);
    }

    private MessageHandlerDecorator decorator(int order, List<Integer> calls) {
        return new MessageHandlerDecorator() {
            @Override
            public int order() {
                return order;
            }

            @Override
            public MessageProcessingStatus handle(
                    MessageHandlerInvocation invocation, MessageHandlerDecoratorChain chain) {
                calls.add(order);
                MessageProcessingStatus outcome = chain.invokeNext(invocation);
                calls.add(-order);
                return outcome;
            }
        };
    }
}
