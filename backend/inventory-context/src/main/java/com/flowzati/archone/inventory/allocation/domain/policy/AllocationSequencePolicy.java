package com.flowzati.archone.inventory.allocation.domain.policy;

/** Order precedence within one owner/location and shared-SKU contention scope. */
public enum AllocationSequencePolicy {
    FIFO,
    DISPATCH_DATE_FIRST
}
