package com.flowzati.archone.inventory.allocation.infrastructure.mapper;

import com.flowzati.archone.inventory.allocation.domain.aggregate.AllocationDemand;
import com.flowzati.archone.inventory.allocation.domain.entity.AllocationDemandLine;
import com.flowzati.archone.inventory.allocation.domain.valueobject.SourceAllocationUnit;
import com.flowzati.archone.inventory.allocation.infrastructure.entity.AllocationDemandEntity;
import com.flowzati.archone.inventory.allocation.infrastructure.entity.AllocationDemandLineEntity;
import java.util.List;

public final class AllocationDemandMapper {

    private AllocationDemandMapper() {}

    public static AllocationDemandEntity toEntity(AllocationDemand demand) {
        return new AllocationDemandEntity(
                demand.id(),
                demand.source().sourceType(),
                demand.source().sourceId(),
                demand.source().allocationUnitKey(),
                demand.ownerId(),
                demand.facilityId(),
                demand.locationId(),
                demand.requiredBy(),
                demand.releasePriority(),
                demand.enqueuedAt(),
                demand.acceptedContentVersion(),
                demand.lines().stream().map(AllocationDemandMapper::toEntity).toList(),
                demand.status(),
                demand.version());
    }

    public static AllocationDemand toDomain(AllocationDemandEntity entity) {
        return AllocationDemand.rehydrate(
                entity.getId(),
                new SourceAllocationUnit(entity.getSourceType(), entity.getSourceId(), entity.getAllocationUnitKey()),
                entity.getOwnerId(),
                entity.getFacilityId(),
                entity.getLocationId(),
                entity.getRequiredBy(),
                entity.getReleasePriority(),
                entity.getEnqueuedAt(),
                entity.getAcceptedContentVersion(),
                toDomainLines(entity),
                entity.getStatus(),
                entity.getVersion());
    }

    private static AllocationDemandLineEntity toEntity(AllocationDemandLine line) {
        return new AllocationDemandLineEntity(
                line.id(), line.sourceLineId(), line.skuCode(), line.quantity(), line.lineSequence());
    }

    private static List<AllocationDemandLine> toDomainLines(AllocationDemandEntity demand) {
        return demand.getLines().stream()
                .map(line -> new AllocationDemandLine(
                        line.getId(),
                        demand.getId(),
                        line.getSourceLineId(),
                        line.getSkuCode(),
                        line.getQuantity(),
                        line.getLineSequence()))
                .toList();
    }
}
