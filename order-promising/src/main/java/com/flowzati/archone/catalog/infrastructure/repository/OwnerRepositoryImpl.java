package com.flowzati.archone.catalog.infrastructure.repository;

import com.flowzati.archone.catalog.domain.aggregate.Owner;
import com.flowzati.archone.catalog.domain.repository.OwnerRepository;
import com.flowzati.archone.catalog.infrastructure.mapper.OwnerMapper;
import com.flowzati.archone.catalog.infrastructure.repository.jpa.JpaOwnerRepository;
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
