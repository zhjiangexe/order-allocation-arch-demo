package com.flowzati.archone.contracts.promising.v3;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.flowzati.archone.messaging.events.IntegrationEvent;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Stock-operation-centric order assignment fact. V1 and V2 remain readable during the compatibility window. */
public final class OrderAllocationCommittedIntegrationEvent extends IntegrationEvent {

    public static final String EVENT_TYPE =
            com.flowzati.archone.contracts.promising.v1.OrderAllocationCommittedIntegrationEvent.EVENT_TYPE;
    public static final int CONTRACT_VERSION = 3;

    private final UUID stockOperationId;
    private final UUID orderId;
    private final UUID ownerId;
    private final UUID facilityId;
    private final UUID stockOperationTypeId;
    private final UUID sourceLocationId;
    private final UUID destinationLocationId;
    private final List<AssignedMove> moves;
    private final Instant dispatchBy;
    private final int releasePriority;
    private final Instant assignedAt;

    @JsonCreator
    public OrderAllocationCommittedIntegrationEvent(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("stockOperationId") UUID stockOperationId,
            @JsonProperty("orderId") UUID orderId,
            @JsonProperty("ownerId") UUID ownerId,
            @JsonProperty("facilityId") UUID facilityId,
            @JsonProperty("stockOperationTypeId") UUID stockOperationTypeId,
            @JsonProperty("sourceLocationId") UUID sourceLocationId,
            @JsonProperty("destinationLocationId") UUID destinationLocationId,
            @JsonProperty("moves") List<AssignedMove> moves,
            @JsonProperty("dispatchBy") Instant dispatchBy,
            @JsonProperty("releasePriority") int releasePriority,
            @JsonProperty("assignedAt") Instant assignedAt) {
        super(eventId);
        if (stockOperationId == null
                || orderId == null
                || ownerId == null
                || facilityId == null
                || stockOperationTypeId == null
                || sourceLocationId == null
                || destinationLocationId == null
                || dispatchBy == null
                || assignedAt == null
                || moves == null
                || moves.isEmpty()) {
            throw new IllegalArgumentException("Order assignment requires operation, route and moves");
        }
        if (releasePriority < 0 || releasePriority > 100) {
            throw new IllegalArgumentException("Release priority must be between 0 and 100");
        }
        moves = List.copyOf(moves);
        HashSet<UUID> moveIds = new HashSet<>();
        if (moves.stream().anyMatch(move -> move == null || !moveIds.add(move.moveId()))) {
            throw new IllegalArgumentException("Order assignment requires unique non-null moves");
        }
        this.stockOperationId = stockOperationId;
        this.orderId = orderId;
        this.ownerId = ownerId;
        this.facilityId = facilityId;
        this.stockOperationTypeId = stockOperationTypeId;
        this.sourceLocationId = sourceLocationId;
        this.destinationLocationId = destinationLocationId;
        this.moves = moves;
        this.dispatchBy = dispatchBy;
        this.releasePriority = releasePriority;
        this.assignedAt = assignedAt;
    }

    public UUID getStockOperationId() {
        return stockOperationId;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public UUID getFacilityId() {
        return facilityId;
    }

    public UUID getStockOperationTypeId() {
        return stockOperationTypeId;
    }

    public UUID getSourceLocationId() {
        return sourceLocationId;
    }

    public UUID getDestinationLocationId() {
        return destinationLocationId;
    }

    public List<AssignedMove> getMoves() {
        return moves;
    }

    public Instant getDispatchBy() {
        return dispatchBy;
    }

    public int getReleasePriority() {
        return releasePriority;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    public record AssignedMove(
            UUID orderLineId, UUID moveId, String skuCode, int quantity, List<BatchPick> batchPicks) {

        @JsonCreator
        public AssignedMove(
                @JsonProperty("orderLineId") UUID orderLineId,
                @JsonProperty("moveId") UUID moveId,
                @JsonProperty("skuCode") String skuCode,
                @JsonProperty("quantity") int quantity,
                @JsonProperty("batchPicks") List<BatchPick> batchPicks) {
            if (orderLineId == null
                    || moveId == null
                    || skuCode == null
                    || skuCode.isBlank()
                    || quantity <= 0
                    || batchPicks == null
                    || batchPicks.isEmpty()) {
                throw new IllegalArgumentException("Assigned move requires source line, move, SKU and batch picks");
            }
            batchPicks = List.copyOf(batchPicks);
            int covered;
            try {
                covered = batchPicks.stream().mapToInt(BatchPick::quantity).reduce(0, Math::addExact);
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("Assigned batch-pick quantity exceeds integer range", overflow);
            }
            if (covered != quantity) {
                throw new IllegalArgumentException("Assigned batch picks must exactly cover the move");
            }
            this.orderLineId = orderLineId;
            this.moveId = moveId;
            this.skuCode = skuCode;
            this.quantity = quantity;
            this.batchPicks = batchPicks;
        }
    }

    public record BatchPick(UUID stockQuantId, int quantity) {

        @JsonCreator
        public BatchPick(@JsonProperty("stockQuantId") UUID stockQuantId, @JsonProperty("quantity") int quantity) {
            if (stockQuantId == null || quantity <= 0) {
                throw new IllegalArgumentException("Batch pick requires stock quant and positive quantity");
            }
            this.stockQuantId = stockQuantId;
            this.quantity = quantity;
        }
    }
}
