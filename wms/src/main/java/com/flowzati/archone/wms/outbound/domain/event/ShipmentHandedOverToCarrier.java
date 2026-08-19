package com.flowzati.archone.wms.outbound.domain.event;

import com.flowzati.archone.wms.shared.domain.WmsDomainEvent;
import java.time.Instant;
import java.util.UUID;

/** WMS 已把貨物 custody 交給承運人；不代表 TMS 的車輛已離站。 */
public record ShipmentHandedOverToCarrier(UUID shipmentId, UUID orderId, Instant occurredAt)
        implements WmsDomainEvent {}
