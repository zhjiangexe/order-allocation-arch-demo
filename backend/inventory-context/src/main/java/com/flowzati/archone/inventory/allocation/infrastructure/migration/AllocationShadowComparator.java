package com.flowzati.archone.inventory.allocation.infrastructure.migration;

import com.flowzati.archone.inventory.allocation.domain.valueobject.SourceAllocationUnit;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only cutover comparator for legacy order rows and allocation-demand snapshots.
 *
 * <p>This class deliberately has no repository or committer dependency. Callers own both reads;
 * the comparator only normalizes the legacy location model and classifies differences.
 */
public final class AllocationShadowComparator {

    public Comparison compare(LegacySnapshot legacy, DemandSnapshot demand) {
        Objects.requireNonNull(legacy, "Legacy allocation snapshot is required");
        Objects.requireNonNull(demand, "Allocation-demand snapshot is required");
        EnumSet<DivergenceCategory> differences = EnumSet.noneOf(DivergenceCategory.class);

        if (!legacy.source().equals(demand.source())
                || !legacy.ownerId().equals(demand.ownerId())
                || !legacy.facilityId().equals(demand.facilityId())
                || !legacy.lines().equals(demand.lines())) {
            differences.add(DivergenceCategory.BLOCKING_DEMAND_CONTENT);
        }
        if (!legacy.normalizedLocationId().equals(demand.locationId())) {
            differences.add(DivergenceCategory.BLOCKING_SOURCE_LOCATION);
        }
        if (!legacy.expandedLocationId().equals(legacy.normalizedLocationId())) {
            differences.add(DivergenceCategory.KNOWN_LEGACY_LOCATION_EXPANSION);
        }
        if (legacy.allocationEligible() != demand.allocationEligible()) {
            if (legacy.allocationEligible()
                    && !demand.allocationEligible()
                    && demand.rejectedOnlyByCrossSkuPredecessor()) {
                differences.add(DivergenceCategory.KNOWN_CROSS_SKU_FIFO_CORRECTION);
            } else {
                differences.add(DivergenceCategory.BLOCKING_ALLOCATION_OUTCOME);
            }
        }
        return new Comparison(differences);
    }

    public enum DivergenceCategory {
        KNOWN_LEGACY_LOCATION_EXPANSION(false),
        KNOWN_CROSS_SKU_FIFO_CORRECTION(false),
        BLOCKING_DEMAND_CONTENT(true),
        BLOCKING_SOURCE_LOCATION(true),
        BLOCKING_ALLOCATION_OUTCOME(true);

        private final boolean blocksCutover;

        DivergenceCategory(boolean blocksCutover) {
            this.blocksCutover = blocksCutover;
        }

        public boolean blocksCutover() {
            return blocksCutover;
        }
    }

    public record Comparison(Set<DivergenceCategory> divergences) {

        public Comparison {
            divergences = Set.copyOf(divergences);
        }

        public boolean blocksCutover() {
            return divergences.stream().anyMatch(DivergenceCategory::blocksCutover);
        }
    }

    public record ShadowLine(String sourceLineId, String skuCode, int quantity) {

        public ShadowLine {
            if (sourceLineId == null
                    || sourceLineId.isBlank()
                    || skuCode == null
                    || skuCode.isBlank()
                    || quantity <= 0) {
                throw new IllegalArgumentException("Valid shadow demand line is required");
            }
        }
    }

    public record LegacySnapshot(
            SourceAllocationUnit source,
            UUID ownerId,
            UUID facilityId,
            UUID expandedLocationId,
            UUID existingExecutionLocationId,
            UUID configuredOutboundLocationId,
            List<ShadowLine> lines,
            boolean allocationEligible) {

        public LegacySnapshot {
            Objects.requireNonNull(source, "Legacy source identity is required");
            Objects.requireNonNull(ownerId, "Legacy owner is required");
            Objects.requireNonNull(facilityId, "Legacy facility is required");
            Objects.requireNonNull(expandedLocationId, "Legacy expanded location is required");
            Objects.requireNonNull(configuredOutboundLocationId, "Configured outbound location is required");
            lines = canonicalLines(lines);
        }

        public UUID normalizedLocationId() {
            return existingExecutionLocationId == null ? configuredOutboundLocationId : existingExecutionLocationId;
        }
    }

    public record DemandSnapshot(
            SourceAllocationUnit source,
            UUID ownerId,
            UUID facilityId,
            UUID locationId,
            List<ShadowLine> lines,
            boolean allocationEligible,
            boolean rejectedOnlyByCrossSkuPredecessor) {

        public DemandSnapshot {
            Objects.requireNonNull(source, "Demand source identity is required");
            Objects.requireNonNull(ownerId, "Demand owner is required");
            Objects.requireNonNull(facilityId, "Demand facility is required");
            Objects.requireNonNull(locationId, "Demand source location is required");
            lines = canonicalLines(lines);
            if (allocationEligible && rejectedOnlyByCrossSkuPredecessor) {
                throw new IllegalArgumentException("An eligible demand cannot be rejected by cross-SKU precedence");
            }
        }
    }

    private static List<ShadowLine> canonicalLines(List<ShadowLine> lines) {
        Objects.requireNonNull(lines, "Shadow demand lines are required");
        return lines.stream()
                .sorted(Comparator.comparing(ShadowLine::sourceLineId))
                .toList();
    }
}
