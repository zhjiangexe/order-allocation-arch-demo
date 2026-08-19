package com.flowzati.archone.inventory.allocation.domain.type;

/** External decision 與 local completion 分開保存，讓 crash recovery 可以續做本地釋放。 */
public enum AllocationCancellationState {
    STARTED,
    EXTERNAL_REJECTED,
    EXTERNAL_CONFIRMED,
    COMPLETED
}
