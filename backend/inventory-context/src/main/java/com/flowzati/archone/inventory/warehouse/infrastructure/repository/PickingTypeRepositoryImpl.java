package com.flowzati.archone.inventory.warehouse.infrastructure.repository;

import com.flowzati.archone.inventory.warehouse.domain.aggregate.PickingDefinition;
import com.flowzati.archone.inventory.warehouse.domain.repository.PickingTypeRepository;
import com.flowzati.archone.inventory.warehouse.domain.type.PickingDirection;
import com.flowzati.archone.inventory.warehouse.infrastructure.mapper.PickingTypeMapper;
import com.flowzati.archone.inventory.warehouse.infrastructure.repository.jpa.JpaPickingTypeRepository;
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
    public void save(PickingDefinition pickingType) {
        repository.save(PickingTypeMapper.toEntity(pickingType));
    }

    @Override
    public Optional<PickingDefinition> find(UUID facilityId, PickingDirection code) {
        if (facilityId == null || code == null) {
            return Optional.empty();
        }
        return repository.findByFacilityIdAndCode(facilityId, code).map(PickingTypeMapper::toDomain);
    }
}
