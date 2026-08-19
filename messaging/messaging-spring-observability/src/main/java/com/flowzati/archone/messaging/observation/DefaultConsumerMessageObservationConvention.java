package com.flowzati.archone.messaging.observation;

import io.micrometer.common.KeyValues;
import io.micrometer.observation.Observation;

/** Default low-cardinality consumer metrics and trace-only message identifiers. */
public final class DefaultConsumerMessageObservationConvention implements ConsumerMessageObservationConvention {

    @Override
    public String getName() {
        return MessagingObservationNames.CONSUMER;
    }

    @Override
    public String getContextualName(ConsumerMessageObservationContext context) {
        return context.messageContext().logicalChannel() + " process";
    }

    @Override
    public KeyValues getLowCardinalityKeyValues(ConsumerMessageObservationContext context) {
        return KeyValues.of(
                MessagingObservationTags.SUBSCRIBER_ID,
                context.messageContext().subscriberId(),
                MessagingObservationTags.LOGICAL_DESTINATION,
                context.messageContext().logicalChannel(),
                MessagingObservationTags.MESSAGE_TYPE,
                MessageObservationKeyValues.messageType(context.message()),
                MessagingObservationTags.OUTCOME,
                context.outcome().tagValue(),
                MessagingObservationTags.EXCEPTION_TYPE,
                MessageObservationKeyValues.exceptionType(context.getError()));
    }

    @Override
    public KeyValues getHighCardinalityKeyValues(ConsumerMessageObservationContext context) {
        return MessageObservationKeyValues.traceOnlyIdentifiers(context.message());
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ConsumerMessageObservationContext;
    }
}
