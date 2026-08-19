package com.flowzati.archone.logisticsdata.infrastructure.repository;

import com.flowzati.archone.logisticsdata.domain.aggregate.Owner;
import com.flowzati.archone.logisticsdata.domain.repository.OwnerRepository;
import com.flowzati.archone.logisticsdata.infrastructure.mapper.OwnerMapper;
import com.flowzati.archone.logisticsdata.infrastructure.repository.jpa.JpaOwnerRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class OwnerRepositoryImpl implements OwnerRepository {

    private final JpaOwnerRepository repository;

    public OwnerRepositoryImpl(JpaOwnerRepository repository) {
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
