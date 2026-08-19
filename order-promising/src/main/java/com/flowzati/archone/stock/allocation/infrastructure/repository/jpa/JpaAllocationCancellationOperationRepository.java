package com.flowzati.archone.stock.allocation.infrastructure.repository.jpa;

import com.flowzati.archone.stock.allocation.infrastructure.entity.AllocationCancellationOperationEntity;
import com.flowzati.archone.stock.allocation.infrastructure.entity.AllocationCancellationOperationKey;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaAllocationCancellationOperationRepository extends JpaRepository<
    AllocationCancellationOperationEntity, AllocationCancellationOperationKey> {
}
