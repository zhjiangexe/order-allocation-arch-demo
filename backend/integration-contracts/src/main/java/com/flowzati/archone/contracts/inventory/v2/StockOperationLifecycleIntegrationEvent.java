package com.flowzati.archone.contracts.inventory.v2;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.flowzati.archone.messaging.events.IntegrationEvent;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Durable Inventory audit fact for a canonical stock-operation lifecycle transition. */
public final class StockOperationLifecycleIntegrationEvent extends IntegrationEvent {

    public static final String EVENT_TYPE = "StockOperationLifecycleIntegrationEvent";
    public static final int CONTRACT_VERSION = 2;

    private final UUID stockOperationId;
    private final UUID stockOperationTypeId;
    private final String sourceType;
    private final String sourceId;
    private final String allocationUnitKey;
    private final LifecycleAction action;
    private final List<MoveSnapshot> moves;
    private final Instant occurredAt;

    @JsonCreator
    public StockOperationLifecycleIntegrationEvent(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("stockOperationId") UUID stockOperationId,
            @JsonProperty("stockOperationTypeId") UUID stockOperationTypeId,
            @JsonProperty("sourceType") String sourceType,
            @JsonProperty("sourceId") String sourceId,
            @JsonProperty("allocationUnitKey") String allocationUnitKey,
            @JsonProperty("action") LifecycleAction action,
            @JsonProperty("moves") List<MoveSnapshot> moves,
            @JsonProperty("occurredAt") Instant occurredAt) {
        super(eventId);
        if (stockOperationId == null
                || stockOperationTypeId == null
                || action == null
                || occurredAt == null
                || moves == null
                || moves.isEmpty()) {
            throw new IllegalArgumentException(
                    "Stock operation lifecycle fact requires operation, operation type, action, moves and time");
        }
        if (sourceType == null
                || sourceType.isBlank()
                || sourceId == null
                || sourceId.isBlank()
                || allocationUnitKey == null
                || allocationUnitKey.isBlank()) {
            throw new IllegalArgumentException("Stock operation lifecycle fact requires canonical source trace");
        }
        moves = List.copyOf(moves);
        HashSet<UUID> moveIds = new HashSet<>();
        if (moves.stream().anyMatch(move -> move == null || !moveIds.add(move.moveId()))) {
            throw new IllegalArgumentException("Stock operation lifecycle fact requires unique non-null moves");
        }
        this.stockOperationId = stockOperationId;
        this.stockOperationTypeId = stockOperationTypeId;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.allocationUnitKey = allocationUnitKey;
        this.action = action;
        this.moves = moves;
        this.occurredAt = occurredAt;
    }

    public UUID getStockOperationId() {
        return stockOperationId;
    }

    public UUID getStockOperationTypeId() {
        return stockOperationTypeId;
    }

    public String getSourceType() {
        return sourceType;
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getAllocationUnitKey() {
        return allocationUnitKey;
    }

    public LifecycleAction getAction() {
        return action;
    }

    public List<MoveSnapshot> getMoves() {
        return moves;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    public enum LifecycleAction {
        RELEASED,
        CANCELLED,
        COMPLETED
    }

    public record MoveSnapshot(
            UUID moveId, String sourceLineId, String skuCode, int quantity, List<BatchSnapshot> batches) {

        @JsonCreator
        public MoveSnapshot(
                @JsonProperty("moveId") UUID moveId,
                @JsonProperty("sourceLineId") String sourceLineId,
                @JsonProperty("skuCode") String skuCode,
                @JsonProperty("quantity") int quantity,
                @JsonProperty("batches") List<BatchSnapshot> batches) {
            if (moveId == null
                    || sourceLineId == null
                    || sourceLineId.isBlank()
                    || skuCode == null
                    || skuCode.isBlank()
                    || quantity <= 0
                    || batches == null) {
                throw new IllegalArgumentException("Lifecycle move snapshot requires source line, SKU and quantity");
            }
            batches = List.copyOf(batches);
            HashSet<UUID> quantIds = new HashSet<>();
            if (batches.stream().anyMatch(batch -> batch == null || !quantIds.add(batch.stockQuantId()))) {
                throw new IllegalArgumentException("Lifecycle move snapshot requires unique non-null batches");
            }
            int covered;
            try {
                covered = batches.stream().mapToInt(BatchSnapshot::quantity).reduce(0, Math::addExact);
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("Lifecycle batch quantity exceeds integer range", overflow);
            }
            if (covered != 0 && covered != quantity) {
                throw new IllegalArgumentException("Lifecycle batches must be empty or exactly cover the move");
            }
            this.moveId = moveId;
            this.sourceLineId = sourceLineId;
            this.skuCode = skuCode;
            this.quantity = quantity;
            this.batches = batches;
        }
    }

    public record BatchSnapshot(UUID stockQuantId, int quantity) {

        @JsonCreator
        public BatchSnapshot(@JsonProperty("stockQuantId") UUID stockQuantId, @JsonProperty("quantity") int quantity) {
            if (stockQuantId == null || quantity <= 0) {
                throw new IllegalArgumentException("Lifecycle batch snapshot requires quant and positive quantity");
            }
            this.stockQuantId = stockQuantId;
            this.quantity = quantity;
        }
    }
}
