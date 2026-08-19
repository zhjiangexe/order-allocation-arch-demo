package com.flowzati.archone.wms.inbound.domain.aggregate;

import com.flowzati.archone.wms.inbound.domain.event.GoodsArrived;
import com.flowzati.archone.wms.inbound.domain.event.InboundOperationRegistered;
import com.flowzati.archone.wms.inbound.domain.event.InspectionPassed;
import com.flowzati.archone.wms.inbound.domain.event.PutawayCompleted;
import com.flowzati.archone.wms.inbound.domain.event.StockQuarantined;
import com.flowzati.archone.wms.inbound.domain.type.InboundStatus;
import com.flowzati.archone.wms.inbound.domain.valueobject.InboundLine;
import com.flowzati.archone.wms.inbound.domain.valueobject.PutawayLine;
import com.flowzati.archone.wms.shared.domain.WmsDomainEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 收貨作業聚合；先用明確 checkpoint 表達流程，不加入複雜 putaway 演算法。 */
public class InboundOperation {

    private final List<WmsDomainEvent> events = new ArrayList<>();
    private final UUID id;
    private final UUID ownerId;
    private final UUID facilityId;
    private final String externalReference;
    private final List<InboundLine> expectedLines;
    private InboundStatus status;

    private InboundOperation(
            UUID id, UUID ownerId, UUID facilityId, String externalReference, List<InboundLine> expectedLines) {
        if (id == null || ownerId == null || facilityId == null) {
            throw new IllegalArgumentException("Inbound operation requires operation, owner and facility IDs");
        }
        if (externalReference == null || externalReference.isBlank()) {
            throw new IllegalArgumentException("Inbound external reference is required");
        }
        if (expectedLines == null || expectedLines.isEmpty()) {
            throw new IllegalArgumentException("Inbound operation requires expected lines");
        }
        this.id = id;
        this.ownerId = ownerId;
        this.facilityId = facilityId;
        this.externalReference = externalReference;
        this.expectedLines = List.copyOf(expectedLines);
        this.status = InboundStatus.REGISTERED;
    }

    public static InboundOperation register(
            UUID id,
            UUID ownerId,
            UUID facilityId,
            String externalReference,
            List<InboundLine> expectedLines,
            Instant registeredAt) {
        requireTime(registeredAt, "Inbound registration time is required");
        InboundOperation operation = new InboundOperation(id, ownerId, facilityId, externalReference, expectedLines);
        operation.events.add(new InboundOperationRegistered(id, ownerId, facilityId, registeredAt));
        return operation;
    }

    public void confirmArrival(Instant arrivedAt) {
        requireTime(arrivedAt, "Arrival time is required");
        if (status == InboundStatus.ARRIVED) {
            return;
        }
        requireStatus(InboundStatus.REGISTERED, "Only a registered inbound operation can arrive");
        status = InboundStatus.ARRIVED;
        events.add(new GoodsArrived(id, arrivedAt));
    }

    public void recordInspection(boolean accepted, String reason, Instant inspectedAt) {
        requireTime(inspectedAt, "Inspection time is required");
        if (status == InboundStatus.READY_FOR_PUTAWAY || status == InboundStatus.QUARANTINED) {
            return;
        }
        requireStatus(InboundStatus.ARRIVED, "Only arrived goods can be inspected");
        if (accepted) {
            status = InboundStatus.READY_FOR_PUTAWAY;
            events.add(new InspectionPassed(id, inspectedAt));
            return;
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Quarantine reason is required when inspection fails");
        }
        status = InboundStatus.QUARANTINED;
        events.add(new StockQuarantined(id, reason, inspectedAt));
    }

    public void completePutaway(List<PutawayLine> actualLines, Instant completedAt) {
        requireTime(completedAt, "Putaway completion time is required");
        if (status == InboundStatus.COMPLETED) {
            return;
        }
        requireStatus(InboundStatus.READY_FOR_PUTAWAY, "Only inspected goods can be put away");
        if (actualLines == null || actualLines.isEmpty()) {
            throw new IllegalArgumentException("Putaway requires actual lines");
        }
        requireExpectedQuantities(actualLines);
        status = InboundStatus.COMPLETED;
        events.add(new PutawayCompleted(id, ownerId, facilityId, actualLines, completedAt));
    }

    private void requireExpectedQuantities(List<PutawayLine> actualLines) {
        Map<String, Integer> expected = quantitiesBySku(expectedLines.stream()
                .map(line -> Map.entry(line.skuCode(), line.expectedQuantity()))
                .toList());
        Map<String, Integer> actual = quantitiesBySku(actualLines.stream()
                .map(line -> Map.entry(line.skuCode(), line.quantity()))
                .toList());
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(
                    "Simple inbound flow requires putaway quantities to match expected quantities");
        }
    }

    private static Map<String, Integer> quantitiesBySku(List<Map.Entry<String, Integer>> lines) {
        Map<String, Integer> quantities = new LinkedHashMap<>();
        lines.forEach(line -> quantities.merge(line.getKey(), line.getValue(), Math::addExact));
        return Map.copyOf(quantities);
    }

    public List<WmsDomainEvent> releaseEvents() {
        List<WmsDomainEvent> released = List.copyOf(events);
        events.clear();
        return released;
    }

    private void requireStatus(InboundStatus expected, String message) {
        if (status != expected) {
            throw new IllegalStateException(message + ", was " + status);
        }
    }

    private static void requireTime(Instant time, String message) {
        if (time == null) {
            throw new IllegalArgumentException(message);
        }
    }

    public UUID id() {
        return id;
    }

    public UUID ownerId() {
        return ownerId;
    }

    public UUID facilityId() {
        return facilityId;
    }

    public String externalReference() {
        return externalReference;
    }

    public List<InboundLine> expectedLines() {
        return expectedLines;
    }

    public InboundStatus status() {
        return status;
    }
}
