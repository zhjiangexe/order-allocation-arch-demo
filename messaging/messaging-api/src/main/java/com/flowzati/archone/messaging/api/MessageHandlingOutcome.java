package com.flowzati.archone.messaging.api;

/** Semantic result a terminal handler can expose without changing the Tram-shaped void callback. */
public enum MessageHandlingOutcome {
  PROCESSED,
  IGNORED_UNHANDLED
}
