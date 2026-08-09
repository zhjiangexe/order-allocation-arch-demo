package com.flowzati.archone.messaging.events;

/** Typed dispatcher behavior when a valid envelope is irrelevant to this subscriber. */
public enum UnhandledEventPolicy {
  FAIL,
  IGNORE_WITH_METRIC
}
