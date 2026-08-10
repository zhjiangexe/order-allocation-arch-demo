package com.flowzati.archone.messaging.observation;

import io.micrometer.common.KeyValues;
import io.micrometer.observation.Observation;

/** Default low-cardinality producer metrics and trace-only message identifiers. */
public final class DefaultProducerMessageObservationConvention
    implements ProducerMessageObservationConvention {

  @Override
  public String getName() {
    return MessagingObservationNames.PRODUCER;
  }

  @Override
  public String getContextualName(ProducerMessageObservationContext context) {
    return context.publicationContext().logicalChannel() + " publish";
  }

  @Override
  public KeyValues getLowCardinalityKeyValues(ProducerMessageObservationContext context) {
    return KeyValues.of(
        MessagingObservationTags.LOGICAL_DESTINATION,
        context.publicationContext().logicalChannel(),
        MessagingObservationTags.MESSAGE_TYPE,
        MessageObservationKeyValues.messageType(context.message()),
        MessagingObservationTags.OUTCOME,
        context.outcome().tagValue(),
        MessagingObservationTags.EXCEPTION_TYPE,
        MessageObservationKeyValues.exceptionType(context.getError()));
  }

  @Override
  public KeyValues getHighCardinalityKeyValues(ProducerMessageObservationContext context) {
    return MessageObservationKeyValues.traceOnlyIdentifiers(context.message());
  }

  @Override
  public boolean supportsContext(Observation.Context context) {
    return context instanceof ProducerMessageObservationContext;
  }
}
