package com.flowzati.archone.messaging.observation;

/** Cardinality-reviewed tag names for messaging observations. */
public final class MessagingObservationTags {

  public static final String SUBSCRIBER_ID = "messaging.subscriber.id";
  public static final String LOGICAL_DESTINATION = "messaging.destination.logical";
  public static final String MESSAGE_TYPE = "messaging.message.type";
  public static final String OUTCOME = "messaging.outcome";
  public static final String EXCEPTION_TYPE = "exception.type";

  public static final String MESSAGE_ID = "messaging.message.id";
  public static final String PARTITION_ID = "messaging.partition.id";
  public static final String CORRELATION_ID = "messaging.correlation.id";
  public static final String KAFKA_PARTITION = "messaging.kafka.partition";
  public static final String KAFKA_OFFSET = "messaging.kafka.offset";
  public static final String RETRY_ATTEMPT = "messaging.retry.attempt";
  public static final String RETRY_BACKOFF_MILLIS = "messaging.retry.backoff.ms";

  public static final String NO_EXCEPTION = "none";
  public static final String UNKNOWN = "unknown";

  private MessagingObservationTags() {
  }
}
