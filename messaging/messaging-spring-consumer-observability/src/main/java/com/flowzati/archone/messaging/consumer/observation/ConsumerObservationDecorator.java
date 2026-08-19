package com.flowzati.archone.messaging.consumer.observation;

import com.flowzati.archone.messaging.consumer.common.MessageHandlerDecorator;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerDecoratorChain;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerDecoratorOrders;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerInvocation;
import com.flowzati.archone.messaging.consumer.common.ProcessingOutcome;
import com.flowzati.archone.messaging.observation.ConsumerMessageObservationContext;
import com.flowzati.archone.messaging.observation.ConsumerMessageObservationConvention;
import com.flowzati.archone.messaging.observation.DefaultConsumerMessageObservationConvention;
import com.flowzati.archone.messaging.observation.MessagingObservationOutcome;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;

/** Measures one final semantic outcome around the existing ordered consumer decorator chain. */
public final class ConsumerObservationDecorator implements MessageHandlerDecorator {

    private static final ConsumerMessageObservationConvention DEFAULT_CONVENTION =
            new DefaultConsumerMessageObservationConvention();

    private final ObservationRegistry observationRegistry;
    private final ConsumerMessageObservationConvention customConvention;

    public ConsumerObservationDecorator(ObservationRegistry observationRegistry) {
        this(observationRegistry, null);
    }

    public ConsumerObservationDecorator(
            ObservationRegistry observationRegistry, ConsumerMessageObservationConvention customConvention) {
        this.observationRegistry = Objects.requireNonNull(observationRegistry, "Observation registry is required");
        this.customConvention = customConvention;
    }

    @Override
    public int order() {
        return MessageHandlerDecoratorOrders.OBSERVATION;
    }

    @Override
    public ProcessingOutcome handle(MessageHandlerInvocation invocation, MessageHandlerDecoratorChain chain) {
        ConsumerMessageObservationContext observationContext =
                new ConsumerMessageObservationContext(invocation.message(), invocation.context());
        Observation observation =
                Observation.start(customConvention, DEFAULT_CONVENTION, () -> observationContext, observationRegistry);

        try (Observation.Scope ignored = observation.openScope()) {
            ProcessingOutcome outcome = chain.invokeNext(invocation);
            observationContext.recordOutcome(toObservationOutcome(outcome));
            return outcome;
        } catch (RuntimeException | Error exception) {
            observationContext.recordOutcome(MessagingObservationOutcome.FAILED);
            observation.error(exception);
            throw exception;
        } finally {
            observation.stop();
        }
    }

    private MessagingObservationOutcome toObservationOutcome(ProcessingOutcome outcome) {
        return switch (outcome) {
            case PROCESSED -> MessagingObservationOutcome.PROCESSED;
            case DUPLICATE -> MessagingObservationOutcome.DUPLICATE;
            case IGNORED_UNHANDLED -> MessagingObservationOutcome.IGNORED_UNHANDLED;
        };
    }
}
