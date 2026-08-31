package com.flowzati.archone.logisticsdata.infrastructure.persistence.jpa.store;

import com.flowzati.archone.logisticsdata.application.store.OwnerStore;
import com.flowzati.archone.logisticsdata.domain.aggregate.Owner;
import com.flowzati.archone.logisticsdata.infrastructure.persistence.jpa.mapper.OwnerMapper;
import com.flowzati.archone.logisticsdata.infrastructure.persistence.jpa.repository.JpaOwnerRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class OwnerStoreAdapter implements OwnerStore {

    private final JpaOwnerRepository repository;

    public OwnerStoreAdapter(JpaOwnerRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(Owner owner) {
        repository.save(OwnerMapper.toEntity(owner));
    }

    @Override
    public Optional<Owner> findById(UUID ownerId) {
        return repository.findById(ownerId).map(OwnerMapper::toDomain);
    }

    @Override
    public List<Owner> findAll() {
        return repository.findAllByOrderByCodeAsc().stream()
                .map(OwnerMapper::toDomain)
                .toList();
    }
}
