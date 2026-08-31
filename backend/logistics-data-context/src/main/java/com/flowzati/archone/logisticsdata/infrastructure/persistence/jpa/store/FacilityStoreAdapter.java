package com.flowzati.archone.logisticsdata.infrastructure.persistence.jpa.store;

import com.flowzati.archone.logisticsdata.application.store.FacilityStore;
import com.flowzati.archone.logisticsdata.domain.aggregate.Facility;
import com.flowzati.archone.logisticsdata.infrastructure.persistence.jpa.mapper.FacilityMapper;
import com.flowzati.archone.logisticsdata.infrastructure.persistence.jpa.model.OwnerFacilityEntity;
import com.flowzati.archone.logisticsdata.infrastructure.persistence.jpa.repository.JpaFacilityRepository;
import com.flowzati.archone.logisticsdata.infrastructure.persistence.jpa.repository.JpaOwnerFacilityRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class FacilityStoreAdapter implements FacilityStore {

    private final JpaFacilityRepository repository;
    private final JpaOwnerFacilityRepository assignmentRepository;

    public FacilityStoreAdapter(JpaFacilityRepository repository, JpaOwnerFacilityRepository assignmentRepository) {
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
