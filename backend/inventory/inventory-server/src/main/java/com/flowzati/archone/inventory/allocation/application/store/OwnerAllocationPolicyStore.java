package com.flowzati.archone.inventory.allocation.application.store;

import com.flowzati.archone.inventory.allocation.domain.enums.AllocationSequencePolicy;
import java.util.UUID;

public interface OwnerAllocationPolicyStore {
    AllocationSequencePolicy find(UUID ownerId);
    /** Update only the owner's selection policy; existing reservations are untouched. */
    void save(UUID ownerId, AllocationSequencePolicy policy);
}
