package com.flowzati.archone.logisticsdata.infrastructure.persistence.jpa.store;

import com.flowzati.archone.logisticsdata.application.store.ProductStore;
import com.flowzati.archone.logisticsdata.domain.aggregate.Product;
import com.flowzati.archone.logisticsdata.infrastructure.persistence.jpa.mapper.ProductMapper;
import com.flowzati.archone.logisticsdata.infrastructure.persistence.jpa.repository.JpaProductRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class ProductStoreAdapter implements ProductStore {

    private final JpaProductRepository repository;

    public ProductStoreAdapter(JpaProductRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(Product product) {
        repository.save(ProductMapper.toEntity(product));
    }

    @Override
    public List<Product> findByOwner(UUID ownerId) {
        return repository.findByOwnerIdOrderByProductCodeAsc(ownerId).stream()
                .map(ProductMapper::toDomain)
                .toList();
    }
}
