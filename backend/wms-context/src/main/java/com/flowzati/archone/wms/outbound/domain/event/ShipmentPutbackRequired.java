package com.flowzati.archone.wms.outbound.domain.event;

import com.flowzati.archone.wms.shared.domain.WmsDomainEvent;
import java.time.Instant;
import java.util.UUID;

/** 貨已被實際揀出，不能只做資料釋放；後續必須建立回架任務。 */
public record ShipmentPutbackRequired(UUID shipmentId, UUID orderId, String requestId, Instant occurredAt)
        implements WmsDomainEvent {}
