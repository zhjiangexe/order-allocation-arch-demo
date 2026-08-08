package com.flowzati.archone.stock.application.event;

import com.flowzati.archone.promising.domain.DomainEvent;

/** Publishes allocation and inventory facts through the transactional Integration Event path. */
@FunctionalInterface
public interface AllocationDomainEventPublisher {

  void publish(DomainEvent event);
}
