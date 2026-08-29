package com.flowzati.archone.contracts.fulfillment.v1;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.flowzati.archone.messaging.events.IntegrationEvent;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** WMS 已完成停止作業與必要 recovery，Shipment 已不可再出貨。 */
public final class ShipmentCancelledIntegrationEvent extends IntegrationEvent {

    public static final String EVENT_TYPE = "ShipmentCancelledIntegrationEvent";

    private final UUID shipmentId;
    private final UUID allocationId;
    private final UUID allocationDemandId;
    private final UUID orderId;
    private final UUID cancellationRequestId;
    private final Instant cancellationRequestedAt;
    private final String cancellationReason;
    private final Instant cancelledAt;

    @JsonCreator
    public ShipmentCancelledIntegrationEvent(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("shipmentId") UUID shipmentId,
            @JsonProperty("allocationId") UUID allocationId,
            @JsonProperty("allocationDemandId") UUID allocationDemandId,
            @JsonProperty("orderId") UUID orderId,
            @JsonProperty("cancellationRequestId") UUID cancellationRequestId,
            @JsonProperty("cancellationRequestedAt") Instant cancellationRequestedAt,
            @JsonProperty("cancellationReason") String cancellationReason,
            @JsonProperty("cancelledAt") Instant cancelledAt) {
        super(eventId);
        this.shipmentId = Objects.requireNonNull(shipmentId, "Shipment ID is required");
        this.allocationId = Objects.requireNonNull(allocationId, "Allocation ID is required");
        this.allocationDemandId = Objects.requireNonNull(allocationDemandId, "Allocation demand ID is required");
        this.orderId = Objects.requireNonNull(orderId, "Order ID is required");
        this.cancellationRequestId =
                Objects.requireNonNull(cancellationRequestId, "Cancellation request ID is required");
        this.cancellationRequestedAt =
                Objects.requireNonNull(cancellationRequestedAt, "Cancellation request time is required");
        if (cancellationReason == null || cancellationReason.isBlank()) {
            throw new IllegalArgumentException("Cancellation reason is required");
        }
        if (cancellationReason.length() > 512) {
            throw new IllegalArgumentException("Cancellation reason must not exceed 512 characters");
        }
        this.cancellationReason = cancellationReason;
        this.cancelledAt = Objects.requireNonNull(cancelledAt, "Cancellation completion time is required");
    }

    public UUID getShipmentId() {
        return shipmentId;
    }

    public UUID getAllocationId() {
        return allocationId;
    }

    public UUID getAllocationDemandId() {
        return allocationDemandId;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getCancellationRequestId() {
        return cancellationRequestId;
    }

    public Instant getCancellationRequestedAt() {
        return cancellationRequestedAt;
    }

    public String getCancellationReason() {
        return cancellationReason;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }
}
