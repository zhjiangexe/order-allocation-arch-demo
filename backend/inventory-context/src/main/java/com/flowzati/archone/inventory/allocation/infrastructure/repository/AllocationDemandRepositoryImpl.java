package com.flowzati.archone.inventory.allocation.infrastructure.repository;

import com.flowzati.archone.inventory.allocation.domain.aggregate.AllocationDemand;
import com.flowzati.archone.inventory.allocation.domain.repository.AllocationDemandRepository;
import com.flowzati.archone.inventory.allocation.domain.valueobject.SourceAllocationUnit;
import com.flowzati.archone.inventory.allocation.infrastructure.mapper.AllocationDemandMapper;
import com.flowzati.archone.inventory.allocation.infrastructure.repository.jpa.JpaAllocationDemandRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class AllocationDemandRepositoryImpl implements AllocationDemandRepository {

    private final JpaAllocationDemandRepository repository;

    public AllocationDemandRepositoryImpl(JpaAllocationDemandRepository repository) {
        this.repository = repository;
    }

    @Override
    public AllocationDemand save(AllocationDemand demand) {
        return AllocationDemandMapper.toDomain(repository.save(AllocationDemandMapper.toEntity(demand)));
    }

    @Override
    public Optional<AllocationDemand> findById(UUID allocationDemandId) {
        return repository.findById(allocationDemandId).map(AllocationDemandMapper::toDomain);
    }

    @Override
    public Optional<AllocationDemand> findBySource(SourceAllocationUnit source) {
        return repository
                .findBySourceTypeAndSourceIdAndAllocationUnitKey(
                        source.sourceType(), source.sourceId(), source.allocationUnitKey())
                .map(AllocationDemandMapper::toDomain);
    }
}
