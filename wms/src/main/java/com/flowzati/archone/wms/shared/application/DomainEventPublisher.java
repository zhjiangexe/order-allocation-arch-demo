package com.flowzati.archone.wms.shared.application;

import com.flowzati.archone.wms.shared.domain.WmsDomainEvent;

/** WMS core 的事件輸出 port；runtime adapter 可接 Outbox、Kafka 或測試 recorder。 */
@FunctionalInterface
public interface DomainEventPublisher {

    void publish(WmsDomainEvent event);
}
