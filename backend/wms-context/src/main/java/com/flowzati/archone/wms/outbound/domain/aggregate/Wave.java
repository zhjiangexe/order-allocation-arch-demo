package com.flowzati.archone.wms.outbound.domain.aggregate;

import com.flowzati.archone.wms.outbound.domain.type.WaveStatus;
import com.flowzati.archone.wms.outbound.domain.valueobject.WaveAssignment;
import com.flowzati.archone.wms.outbound.domain.valueobject.WavePlanningPolicy;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 多張 Shipment 的 picking release aggregate。
 *
 * <p>第一版 Wave 有容量上限，Assignments 可安全放在 aggregate 內；日後若單一 Wave 成長到數千張
 * Shipment，應把 assignment persistence 分頁化，而不是無限制放大 aggregate。
 */
public class Wave {

    private final UUID id;
    private final UUID facilityId;
    private final String templateCode;
    private final Instant dispatchByCutoff;
    private final int maxShipments;
    private final int maxLines;
    private final int maxUnits;
    private final List<WaveAssignment> assignments;
    private final Instant plannedAt;
    private WaveStatus status;
    private int warehouseWorkCount;
    private int pickTaskCount;

    private Wave(
            UUID id,
            UUID facilityId,
            String templateCode,
            WavePlanningPolicy policy,
            List<WaveAssignment> assignments,
            Instant plannedAt) {
        if (id == null || facilityId == null || policy == null || plannedAt == null) {
            throw new IllegalArgumentException("Wave requires IDs, policy and planning time");
        }
        if (templateCode == null || templateCode.isBlank()) {
            throw new IllegalArgumentException("Wave template code is required");
        }
        if (!facilityId.equals(policy.facilityId())) {
            throw new IllegalArgumentException("Wave facility must match planning policy");
        }
        if (assignments == null || assignments.isEmpty()) {
            throw new IllegalArgumentException("Wave requires at least one Shipment assignment");
        }
        requireUniqueShipments(assignments);
        requireWithinPolicy(assignments, policy);
        this.id = id;
        this.facilityId = facilityId;
        this.templateCode = templateCode;
        this.dispatchByCutoff = policy.dispatchByCutoff();
        this.maxShipments = policy.maxShipments();
        this.maxLines = policy.maxLines();
        this.maxUnits = policy.maxUnits();
        this.assignments = List.copyOf(assignments);
        this.plannedAt = plannedAt;
        this.status = WaveStatus.PLANNED;
    }

    public static Wave plan(
            UUID id,
            UUID facilityId,
            String templateCode,
            WavePlanningPolicy policy,
            List<WaveAssignment> assignments,
            Instant plannedAt) {
        return new Wave(id, facilityId, templateCode, policy, assignments, plannedAt);
    }

    /** 由 persistence adapter 還原 aggregate，不重播 Wave commands。 */
    public static Wave rehydrate(
            UUID id,
            UUID facilityId,
            String templateCode,
            WavePlanningPolicy policy,
            List<WaveAssignment> assignments,
            Instant plannedAt,
            WaveStatus status,
            int warehouseWorkCount,
            int pickTaskCount) {
        Wave wave = new Wave(id, facilityId, templateCode, policy, assignments, plannedAt);
        if (status == null || warehouseWorkCount < 0 || pickTaskCount < 0) {
            throw new IllegalArgumentException("Persisted Wave state is invalid");
        }
        wave.status = status;
        wave.warehouseWorkCount = warehouseWorkCount;
        wave.pickTaskCount = pickTaskCount;
        return wave;
    }

    public void release(int warehouseWorkCount, int pickTaskCount, Instant releasedAt) {
        requireTime(releasedAt, "Wave release time is required");
        if (status == WaveStatus.RELEASED || status == WaveStatus.COMPLETED) {
            return;
        }
        if (warehouseWorkCount < 0
                || warehouseWorkCount > assignments.size()
                || pickTaskCount < warehouseWorkCount
                || (warehouseWorkCount == 0 && pickTaskCount != 0)) {
            throw new IllegalArgumentException(
                    "Wave release counts must fit assignments and create at least one task per work");
        }
        this.warehouseWorkCount = warehouseWorkCount;
        this.pickTaskCount = pickTaskCount;
        this.status = WaveStatus.RELEASED;
    }

    public void complete(Instant completedAt) {
        requireTime(completedAt, "Wave completion time is required");
        if (status == WaveStatus.COMPLETED) {
            return;
        }
        if (status != WaveStatus.RELEASED) {
            throw new IllegalStateException("Only a released Wave can complete, was " + status);
        }
        status = WaveStatus.COMPLETED;
    }

    private static void requireUniqueShipments(List<WaveAssignment> assignments) {
        Set<UUID> shipmentIds = new HashSet<>();
        for (WaveAssignment assignment : assignments) {
            if (assignment == null || !shipmentIds.add(assignment.shipmentId())) {
                throw new IllegalArgumentException("Wave cannot contain null or duplicate Shipment assignments");
            }
        }
    }

    private static void requireWithinPolicy(List<WaveAssignment> assignments, WavePlanningPolicy policy) {
        int totalLines = assignments.stream().map(WaveAssignment::lineCount).reduce(0, Math::addExact);
        int totalUnits = assignments.stream().map(WaveAssignment::unitCount).reduce(0, Math::addExact);
        boolean missesCutoff = assignments.stream()
                .anyMatch(assignment -> assignment.dispatchBy().isAfter(policy.dispatchByCutoff()));
        if (assignments.size() > policy.maxShipments()
                || totalLines > policy.maxLines()
                || totalUnits > policy.maxUnits()
                || missesCutoff) {
            throw new IllegalArgumentException("Wave assignments exceed planning policy");
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

    public UUID facilityId() {
        return facilityId;
    }

    public String templateCode() {
        return templateCode;
    }

    public Instant dispatchByCutoff() {
        return dispatchByCutoff;
    }

    public int maxShipments() {
        return maxShipments;
    }

    public int maxLines() {
        return maxLines;
    }

    public int maxUnits() {
        return maxUnits;
    }

    public List<WaveAssignment> assignments() {
        return assignments;
    }

    public Instant plannedAt() {
        return plannedAt;
    }

    public WaveStatus status() {
        return status;
    }

    public int warehouseWorkCount() {
        return warehouseWorkCount;
    }

    public int pickTaskCount() {
        return pickTaskCount;
    }
}
