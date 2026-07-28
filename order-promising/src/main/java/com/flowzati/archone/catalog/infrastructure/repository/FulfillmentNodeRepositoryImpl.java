package com.flowzati.archone.catalog.infrastructure.repository;

import com.flowzati.archone.catalog.domain.model.FulfillmentNode;
import com.flowzati.archone.catalog.domain.repository.FulfillmentNodeRepository;
import com.flowzati.archone.catalog.infrastructure.entity.OwnerNodeEntity;
import com.flowzati.archone.catalog.infrastructure.mapper.FulfillmentNodeMapper;
import com.flowzati.archone.catalog.infrastructure.repository.jpa.JpaFulfillmentNodeRepository;
import com.flowzati.archone.catalog.infrastructure.repository.jpa.JpaOwnerNodeRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class FulfillmentNodeRepositoryImpl implements FulfillmentNodeRepository {

  private final JpaFulfillmentNodeRepository repository;
  private final JpaOwnerNodeRepository assignmentRepository;

  public FulfillmentNodeRepositoryImpl(
      JpaFulfillmentNodeRepository repository,
      JpaOwnerNodeRepository assignmentRepository
  ) {
    this.repository = repository;
    this.assignmentRepository = assignmentRepository;
  }

  @Override
  public void save(FulfillmentNode node) {
    repository.save(FulfillmentNodeMapper.toEntity(node));
  }

  @Override
  public void assign(UUID ownerId, UUID nodeId) {
    assignmentRepository.save(new OwnerNodeEntity(ownerId, nodeId));
  }

  @Override
  public Optional<FulfillmentNode> findById(UUID nodeId) {
    return repository.findById(nodeId).map(FulfillmentNodeMapper::toDomain);
  }

  @Override
  public Optional<FulfillmentNode> findByCode(String code) {
    return repository.findByCode(code).map(FulfillmentNodeMapper::toDomain);
  }

  @Override
  public List<FulfillmentNode> findByOwner(UUID ownerId) {
    return repository.findAssignedTo(ownerId).stream()
        .map(FulfillmentNodeMapper::toDomain)
        .toList();
  }
}
