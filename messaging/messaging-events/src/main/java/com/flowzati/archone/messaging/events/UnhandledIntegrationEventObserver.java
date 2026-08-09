package com.flowzati.archone.messaging.events;

/** Records a metric/log before an explicitly ignored shared-channel event is acknowledged. */
@FunctionalInterface
public interface UnhandledIntegrationEventObserver {

  void onUnhandled(UnhandledIntegrationEvent event);
}
