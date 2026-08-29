package com.flowzati.archone.contracts.fulfillment.v2;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.flowzati.archone.messaging.events.IntegrationEvent;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** WMS handed one picking-backed Shipment to a carrier. */
public final class ShipmentHandedOverIntegrationEvent extends IntegrationEvent {

    public static final String EVENT_TYPE =
            com.flowzati.archone.contracts.fulfillment.v1.ShipmentHandedOverIntegrationEvent.EVENT_TYPE;
    public static final int CONTRACT_VERSION = 2;

    private final UUID shipmentId;
    private final UUID pickingId;
    private final UUID orderId;
    private final List<UUID> movementIds;
    private final Instant handedOverAt;

    @JsonCreator
    public ShipmentHandedOverIntegrationEvent(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("shipmentId") UUID shipmentId,
            @JsonProperty("pickingId") UUID pickingId,
            @JsonProperty("orderId") UUID orderId,
            @JsonProperty("movementIds") List<UUID> movementIds,
            @JsonProperty("handedOverAt") Instant handedOverAt) {
        super(eventId);
        this.shipmentId = Objects.requireNonNull(shipmentId, "Shipment ID is required");
        this.pickingId = Objects.requireNonNull(pickingId, "Picking ID is required");
        this.orderId = Objects.requireNonNull(orderId, "Order ID is required");
        this.movementIds = List.copyOf(Objects.requireNonNull(movementIds, "Movement IDs are required"));
        this.handedOverAt = Objects.requireNonNull(handedOverAt, "Handover time is required");
        if (this.movementIds.isEmpty() || this.movementIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("At least one movement ID is required");
        }
        if (new HashSet<>(this.movementIds).size() != this.movementIds.size()) {
            throw new IllegalArgumentException("Movement IDs must be unique");
        }
    }

    public UUID getShipmentId() {
        return shipmentId;
    }

    public UUID getPickingId() {
        return pickingId;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public List<UUID> getMovementIds() {
        return movementIds;
    }

    public Instant getHandedOverAt() {
        return handedOverAt;
    }

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }
}
