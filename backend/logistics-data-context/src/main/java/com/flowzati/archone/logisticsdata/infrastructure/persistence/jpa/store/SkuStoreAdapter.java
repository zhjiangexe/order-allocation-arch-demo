package com.flowzati.archone.logisticsdata.infrastructure.persistence.jpa.store;

import com.flowzati.archone.logisticsdata.application.store.SkuStore;
import com.flowzati.archone.logisticsdata.domain.aggregate.Sku;
import com.flowzati.archone.logisticsdata.infrastructure.persistence.jpa.mapper.SkuMapper;
import com.flowzati.archone.logisticsdata.infrastructure.persistence.jpa.repository.JpaSkuRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class SkuStoreAdapter implements SkuStore {

    private final JpaSkuRepository repository;

    public SkuStoreAdapter(JpaSkuRepository repository) {
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
