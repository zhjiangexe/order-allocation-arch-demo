package com.flowzati.archone.stock.infrastructure.repository.jpa;

import com.flowzati.archone.stock.infrastructure.entity.AllocationCancellationOperationEntity;
import com.flowzati.archone.stock.infrastructure.entity.AllocationCancellationOperationKey;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaAllocationCancellationOperationRepository extends JpaRepository<
    AllocationCancellationOperationEntity, AllocationCancellationOperationKey> {
}
