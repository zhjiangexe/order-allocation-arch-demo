package com.flowzati.archone.catalog.infrastructure.repository;

import com.flowzati.archone.catalog.domain.aggregate.Sku;
import com.flowzati.archone.catalog.domain.repository.SkuRepository;
import com.flowzati.archone.catalog.infrastructure.mapper.SkuMapper;
import com.flowzati.archone.catalog.infrastructure.repository.jpa.JpaSkuRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class SkuRepositoryImpl implements SkuRepository {

  private final JpaSkuRepository repository;

  public SkuRepositoryImpl(JpaSkuRepository repository) {
    this.repository = repository;
  }

  @Override
  public void save(Sku sku) {
    repository.save(SkuMapper.toEntity(sku));
  }

  @Override
  public List<Sku> findByProduct(UUID ownerId, String productCode) {
    return repository.findByOwnerIdAndProductCodeOrderBySkuCodeAsc(ownerId, productCode).stream()
        .map(SkuMapper::toDomain)
        .toList();
  }
}
