package com.flowzati.archone.ordering.application.event;

import com.flowzati.archone.promising.domain.DomainEvent;
import java.util.Collection;

/**
 * Ordering application port for publishing domain facts produced in the current transaction.
 *
 * <p>The implementation translates them into stable Integration Events and writes them through the
 * transactional messaging publisher. This is intentionally not a Spring application-event bus.
 */
@FunctionalInterface
public interface OrderingDomainEventPublisher {

  void publish(DomainEvent event);

  default void publishAll(Collection<? extends DomainEvent> events) {
    if (events == null) {
      throw new IllegalArgumentException("Ordering domain events are required");
    }
    events.forEach(this::publish);
  }
}
