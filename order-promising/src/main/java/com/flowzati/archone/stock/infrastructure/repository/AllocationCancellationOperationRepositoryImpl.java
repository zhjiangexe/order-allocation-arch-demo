package com.flowzati.archone.stock.infrastructure.repository;

import com.flowzati.archone.stock.domain.model.AllocationCancellationOperation;
import com.flowzati.archone.stock.domain.repository.AllocationCancellationOperationRepository;
import com.flowzati.archone.stock.infrastructure.entity.AllocationCancellationOperationKey;
import com.flowzati.archone.stock.infrastructure.mapper.AllocationCancellationOperationMapper;
import com.flowzati.archone.stock.infrastructure.repository.jpa.JpaAllocationCancellationOperationRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class AllocationCancellationOperationRepositoryImpl
    implements AllocationCancellationOperationRepository {

  private final JpaAllocationCancellationOperationRepository repository;

  public AllocationCancellationOperationRepositoryImpl(
      JpaAllocationCancellationOperationRepository repository) {
    this.repository = repository;
  }

  @Override
  public AllocationCancellationOperation save(AllocationCancellationOperation operation) {
    return AllocationCancellationOperationMapper.toDomain(
        repository.save(AllocationCancellationOperationMapper.toEntity(operation)));
  }

  @Override
  public Optional<AllocationCancellationOperation> find(
      UUID allocationDemandId, UUID operationId) {
    return repository.findById(new AllocationCancellationOperationKey(
            allocationDemandId, operationId))
        .map(AllocationCancellationOperationMapper::toDomain);
  }
}
