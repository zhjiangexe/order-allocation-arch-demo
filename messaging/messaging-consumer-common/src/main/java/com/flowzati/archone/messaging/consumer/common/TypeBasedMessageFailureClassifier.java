package com.flowzati.archone.messaging.consumer.common;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Transport-neutral exception classifier.
 *
 * <p>The closest configured exception superclass wins. If the outer exception is not configured,
 * its cause chain is inspected before the fallback classification is returned.
 */
public final class TypeBasedMessageFailureClassifier implements MessageFailureClassifier {

  private final Map<Class<? extends Throwable>, MessageFailureClassification> classifications;
  private final MessageFailureClassification fallback;

  private TypeBasedMessageFailureClassifier(Builder builder) {
    this.classifications = Map.copyOf(builder.classifications);
    this.fallback = builder.fallback;
  }

  public static Builder builder() {
    return new Builder();
  }

  @Override
  public MessageFailureClassification classify(Throwable failure) {
    Objects.requireNonNull(failure, "Message processing failure is required");
    Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    Throwable candidate = failure;
    while (candidate != null && visited.add(candidate)) {
      MessageFailureClassification match = classificationFor(candidate.getClass());
      if (match != null) {
        return match;
      }
      candidate = candidate.getCause();
    }
    return fallback;
  }

  private MessageFailureClassification classificationFor(Class<?> failureType) {
    Class<?> candidateType = failureType;
    while (candidateType != null && Throwable.class.isAssignableFrom(candidateType)) {
      MessageFailureClassification match = classifications.get(candidateType);
      if (match != null) {
        return match;
      }
      candidateType = candidateType.getSuperclass();
    }
    return null;
  }

  /** Mutable construction step; {@link #build()} returns an immutable classifier. */
  public static final class Builder {

    private final Map<Class<? extends Throwable>, MessageFailureClassification> classifications =
        new LinkedHashMap<>();
    private MessageFailureClassification fallback =
        MessageFailureClassification.nonRetryable(MessageFailureCategory.HANDLER);

    private Builder() {
    }

    public Builder retryable(
        Class<? extends Throwable> failureType,
        MessageFailureCategory category
    ) {
      return classify(failureType, MessageFailureClassification.retryable(category));
    }

    public Builder nonRetryable(
        Class<? extends Throwable> failureType,
        MessageFailureCategory category
    ) {
      return classify(failureType, MessageFailureClassification.nonRetryable(category));
    }

    public Builder classify(
        Class<? extends Throwable> failureType,
        MessageFailureClassification classification
    ) {
      Objects.requireNonNull(failureType, "Message failure type is required");
      Objects.requireNonNull(classification, "Message failure classification is required");
      MessageFailureClassification previous = classifications.putIfAbsent(
          failureType, classification);
      if (previous != null) {
        throw new IllegalArgumentException(
            "Message failure type is already classified: " + failureType.getName());
      }
      return this;
    }

    public Builder fallback(MessageFailureClassification fallback) {
      this.fallback = Objects.requireNonNull(
          fallback, "Fallback message failure classification is required");
      return this;
    }

    public TypeBasedMessageFailureClassifier build() {
      return new TypeBasedMessageFailureClassifier(this);
    }
  }
}
