package com.flowzati.archone.messaging.events;

/** Why a syntactically valid Integration Event envelope has no local handler. */
public enum UnhandledIntegrationEventReason {
  UNKNOWN_TYPE_VERSION,
  NO_HANDLER_FOR_DESTINATION
}
