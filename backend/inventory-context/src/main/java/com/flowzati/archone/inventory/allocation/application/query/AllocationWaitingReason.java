package com.flowzati.archone.inventory.allocation.application.query;

/** 操作台對 pending demand 的即時解釋；不是持久化 lifecycle。 */
public enum AllocationWaitingReason {
    WAITING_FOR_EARLIER_DEMAND,
    NO_ALLOCATABLE_STOCK,
    INSUFFICIENT_ATP,
    READY_TO_ALLOCATE
}
