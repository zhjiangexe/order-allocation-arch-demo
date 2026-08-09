package com.flowzati.archone.messaging.consumer.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TypeBasedMessageFailureClassifierTest {

  @Test
  void usesClosestConfiguredSuperclass() {
    TypeBasedMessageFailureClassifier classifier = TypeBasedMessageFailureClassifier.builder()
        .retryable(RuntimeException.class, MessageFailureCategory.INFRASTRUCTURE)
        .nonRetryable(IllegalArgumentException.class, MessageFailureCategory.CONTRACT)
        .build();

    assertThat(classifier.classify(new NumberFormatException("invalid")))
        .isEqualTo(MessageFailureClassification.nonRetryable(MessageFailureCategory.CONTRACT));
  }

  @Test
  void inspectsWrappedCauseBeforeUsingFallback() {
    TypeBasedMessageFailureClassifier classifier = TypeBasedMessageFailureClassifier.builder()
        .retryable(TransientFailure.class, MessageFailureCategory.HANDLER)
        .fallback(MessageFailureClassification.nonRetryable(MessageFailureCategory.CONTRACT))
        .build();

    assertThat(classifier.classify(
        new IllegalStateException("listener wrapper", new TransientFailure())))
        .isEqualTo(MessageFailureClassification.retryable(MessageFailureCategory.HANDLER));
  }

  @Test
  void rejectsDuplicateFailureTypeConfiguration() {
    TypeBasedMessageFailureClassifier.Builder builder =
        TypeBasedMessageFailureClassifier.builder()
            .retryable(RuntimeException.class, MessageFailureCategory.INFRASTRUCTURE);

    assertThatThrownBy(() -> builder.nonRetryable(
        RuntimeException.class, MessageFailureCategory.CONTRACT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(RuntimeException.class.getName());
  }

  private static final class TransientFailure extends RuntimeException {
  }
}
