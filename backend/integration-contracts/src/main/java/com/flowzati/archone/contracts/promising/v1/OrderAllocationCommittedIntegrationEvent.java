package com.flowzati.archone.contracts.promising.v1;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.flowzati.archone.messaging.events.IntegrationEvent;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 訂單配置已提交的 canonical fact。
 *
 * <p>Ordering 只需用 orderId 與 committedAt 推進訂單狀態；WMS 或 Temporal fulfillment driver
 * 則可直接使用同一份不可變配置快照繼續履約，不需要回頭同步查詢 allocation context。
 */
public final class OrderAllocationCommittedIntegrationEvent extends IntegrationEvent {

    public static final String EVENT_TYPE = "OrderAllocationCommittedIntegrationEvent";

    private final UUID allocationId;
    private final UUID orderId;
    private final UUID ownerId;
    private final UUID facilityId;
    private final List<AllocationLine> lines;
    private final Instant dispatchBy;
    private final int releasePriority;
    private final Instant committedAt;

    @JsonCreator
    public OrderAllocationCommittedIntegrationEvent(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("allocationId") UUID allocationId,
            @JsonProperty("orderId") UUID orderId,
            @JsonProperty("ownerId") UUID ownerId,
            @JsonProperty("facilityId") UUID facilityId,
            @JsonProperty("lines") List<AllocationLine> lines,
            @JsonProperty("dispatchBy") Instant dispatchBy,
            @JsonProperty("releasePriority") int releasePriority,
            @JsonProperty("committedAt") Instant committedAt) {
        super(eventId);
        if (allocationId == null || orderId == null || ownerId == null || facilityId == null) {
            throw new IllegalArgumentException("Order allocation commitment requires all business IDs");
        }
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("Order allocation commitment requires allocation lines");
        }
        List<AllocationLine> copiedLines = List.copyOf(lines);
        if (copiedLines.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Order allocation commitment cannot contain null lines");
        }
        Set<UUID> moveIds = new HashSet<>();
        if (copiedLines.stream().anyMatch(line -> !moveIds.add(line.moveId()))) {
            throw new IllegalArgumentException("Order allocation commitment requires unique move IDs");
        }
        if (committedAt == null || dispatchBy == null) {
            throw new IllegalArgumentException(
                    "Order allocation commitment requires commit time and dispatch deadline");
        }
        if (releasePriority < 0 || releasePriority > 100) {
            throw new IllegalArgumentException("Release priority must be between 0 and 100");
        }
        this.allocationId = allocationId;
        this.orderId = orderId;
        this.ownerId = ownerId;
        this.facilityId = facilityId;
        this.lines = copiedLines;
        this.dispatchBy = dispatchBy;
        this.releasePriority = releasePriority;
        this.committedAt = committedAt;
    }

    public UUID getAllocationId() {
        return allocationId;
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

    public List<AllocationLine> getLines() {
        return lines;
    }

    public Instant getDispatchBy() {
        return dispatchBy;
    }

    public int getReleasePriority() {
        return releasePriority;
    }

    public Instant getCommittedAt() {
        return committedAt;
    }

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    /** 一筆已鎖定的 outbound move；source location 是 WMS 建立 PickTask 的起點。 */
    public record AllocationLine(UUID orderLineId, UUID moveId, String skuCode, UUID sourceLocationId, int quantity) {

        @JsonCreator
        public AllocationLine(
                @JsonProperty("orderLineId") UUID orderLineId,
                @JsonProperty("moveId") UUID moveId,
                @JsonProperty("skuCode") String skuCode,
                @JsonProperty("sourceLocationId") UUID sourceLocationId,
                @JsonProperty("quantity") int quantity) {
            if (orderLineId == null || moveId == null || sourceLocationId == null) {
                throw new IllegalArgumentException("Allocation line requires order line, move and source location IDs");
            }
            if (skuCode == null || skuCode.isBlank() || quantity <= 0) {
                throw new IllegalArgumentException("Allocation line requires SKU and positive quantity");
            }
            this.orderLineId = orderLineId;
            this.moveId = moveId;
            this.skuCode = skuCode;
            this.sourceLocationId = sourceLocationId;
            this.quantity = quantity;
        }
    }
}
