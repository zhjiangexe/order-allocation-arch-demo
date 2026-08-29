package com.flowzati.archone.contracts.fulfillment.v3;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.flowzati.archone.messaging.events.IntegrationEvent;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** WMS completed cancellation and recovery for a stock-operation-backed Shipment. */
public final class ShipmentCancelledIntegrationEvent extends IntegrationEvent {

    public static final String EVENT_TYPE =
            com.flowzati.archone.contracts.fulfillment.v1.ShipmentCancelledIntegrationEvent.EVENT_TYPE;
    public static final int CONTRACT_VERSION = 3;

    private final UUID shipmentId;
    private final UUID stockOperationId;
    private final UUID orderId;
    private final UUID cancellationRequestId;
    private final Instant cancellationRequestedAt;
    private final String cancellationReason;
    private final Instant cancelledAt;

    @JsonCreator
    public ShipmentCancelledIntegrationEvent(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("shipmentId") UUID shipmentId,
            @JsonProperty("stockOperationId") UUID stockOperationId,
            @JsonProperty("orderId") UUID orderId,
            @JsonProperty("cancellationRequestId") UUID cancellationRequestId,
            @JsonProperty("cancellationRequestedAt") Instant cancellationRequestedAt,
            @JsonProperty("cancellationReason") String cancellationReason,
            @JsonProperty("cancelledAt") Instant cancelledAt) {
        super(eventId);
        this.shipmentId = Objects.requireNonNull(shipmentId, "Shipment ID is required");
        this.stockOperationId = Objects.requireNonNull(stockOperationId, "Stock operation ID is required");
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

    public UUID getStockOperationId() {
        return stockOperationId;
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
