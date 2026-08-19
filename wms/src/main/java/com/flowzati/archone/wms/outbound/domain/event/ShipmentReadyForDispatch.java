package com.flowzati.archone.wms.outbound.domain.event;

import com.flowzati.archone.wms.shared.domain.WmsDomainEvent;
import java.time.Instant;
import java.util.UUID;

/** Shipment 已通過 WMS 的 Pick、Pack、Stage invariant，可進入交接區。 */
public record ShipmentReadyForDispatch(UUID shipmentId, UUID orderId, Instant occurredAt) implements WmsDomainEvent {}
