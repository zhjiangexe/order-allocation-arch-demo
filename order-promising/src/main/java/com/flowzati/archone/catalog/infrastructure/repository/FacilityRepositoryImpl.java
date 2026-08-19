package com.flowzati.archone.catalog.infrastructure.repository;

import com.flowzati.archone.catalog.domain.aggregate.Facility;
import com.flowzati.archone.catalog.domain.repository.FacilityRepository;
import com.flowzati.archone.catalog.infrastructure.entity.OwnerFacilityEntity;
import com.flowzati.archone.catalog.infrastructure.mapper.FacilityMapper;
import com.flowzati.archone.catalog.infrastructure.repository.jpa.JpaFacilityRepository;
import com.flowzati.archone.catalog.infrastructure.repository.jpa.JpaOwnerFacilityRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class FacilityRepositoryImpl implements FacilityRepository {

  private final JpaFacilityRepository repository;
  private final JpaOwnerFacilityRepository assignmentRepository;

  public FacilityRepositoryImpl(
      JpaFacilityRepository repository,
      JpaOwnerFacilityRepository assignmentRepository
  ) {
    this.repository = repository;
    this.assignmentRepository = assignmentRepository;
  }

  @Override
  public void save(Facility facility) {
    repository.save(FacilityMapper.toEntity(facility));
  }

  @Override
  public void assign(UUID ownerId, UUID facilityId) {
    assignmentRepository.save(new OwnerFacilityEntity(ownerId, facilityId));
  }

  @Override
  public Optional<Facility> findById(UUID facilityId) {
    return repository.findById(facilityId).map(FacilityMapper::toDomain);
  }

  @Override
  public Optional<Facility> findByCode(String code) {
    return repository.findByCode(code).map(FacilityMapper::toDomain);
  }

  @Override
  public List<Facility> findByOwner(UUID ownerId) {
    return repository.findAssignedTo(ownerId).stream()
        .map(FacilityMapper::toDomain)
        .toList();
  }
}
