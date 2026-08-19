package com.flowzati.archone.inventory.allocation.domain.type;

/** Allocation-only lifecycle；履約完成與 physical compensation 不在此狀態機。 */
public enum AllocationDemandStatus {
    PENDING,
    ALLOCATED,
    CANCELLED
}
