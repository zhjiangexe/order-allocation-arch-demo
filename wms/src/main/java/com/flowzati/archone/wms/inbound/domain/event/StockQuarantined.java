package com.flowzati.archone.wms.inbound.domain.event;

import com.flowzati.archone.wms.shared.domain.WmsDomainEvent;
import java.time.Instant;
import java.util.UUID;

public record StockQuarantined(UUID inboundOperationId, String reason, Instant occurredAt) implements WmsDomainEvent {}
