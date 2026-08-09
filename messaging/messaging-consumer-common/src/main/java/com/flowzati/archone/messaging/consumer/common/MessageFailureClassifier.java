package com.flowzati.archone.messaging.consumer.common;

/** Classifies a handler failure without depending on a broker or retry implementation. */
@FunctionalInterface
public interface MessageFailureClassifier {

  MessageFailureClassification classify(Throwable failure);
}
