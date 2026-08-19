package com.flowzati.archone.messaging.events;

/** Receives reason-rich diagnostics before an unhandled Integration Event is acknowledged. */
@FunctionalInterface
public interface UnhandledIntegrationEventObserver {

    void onUnhandled(UnhandledIntegrationEvent event);
}
