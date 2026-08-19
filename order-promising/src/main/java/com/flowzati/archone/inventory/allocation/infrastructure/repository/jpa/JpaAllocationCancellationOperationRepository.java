package com.flowzati.archone.inventory.allocation.infrastructure.repository.jpa;

import com.flowzati.archone.inventory.allocation.infrastructure.entity.AllocationCancellationOperationEntity;
import com.flowzati.archone.inventory.allocation.infrastructure.entity.AllocationCancellationOperationKey;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaAllocationCancellationOperationRepository extends JpaRepository<
    AllocationCancellationOperationEntity, AllocationCancellationOperationKey> {
}
