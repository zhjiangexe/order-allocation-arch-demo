package com.flowzati.archone.catalog.infrastructure.repository;

import com.flowzati.archone.catalog.domain.aggregate.PickingType;
import com.flowzati.archone.catalog.domain.repository.PickingTypeRepository;
import com.flowzati.archone.catalog.domain.type.PickingDirection;
import com.flowzati.archone.catalog.infrastructure.mapper.PickingTypeMapper;
import com.flowzati.archone.catalog.infrastructure.repository.jpa.JpaPickingTypeRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class PickingTypeRepositoryImpl implements PickingTypeRepository {

    private final JpaPickingTypeRepository repository;

    public PickingTypeRepositoryImpl(JpaPickingTypeRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(PickingType pickingType) {
        repository.save(PickingTypeMapper.toEntity(pickingType));
    }

    @Override
    public Optional<PickingType> find(UUID facilityId, PickingDirection code) {
        if (facilityId == null || code == null) {
            return Optional.empty();
        }
        return repository.findByFacilityIdAndCode(facilityId, code).map(PickingTypeMapper::toDomain);
    }
}
