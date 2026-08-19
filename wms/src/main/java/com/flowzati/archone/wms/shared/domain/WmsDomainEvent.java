package com.flowzati.archone.wms.shared.domain;

import java.time.Instant;

/** WMS bounded contexts 共用的最小 Domain Event 契約。 */
public interface WmsDomainEvent {

    Instant occurredAt();
}
