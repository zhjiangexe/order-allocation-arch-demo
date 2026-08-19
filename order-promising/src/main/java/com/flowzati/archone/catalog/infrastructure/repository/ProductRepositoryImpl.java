package com.flowzati.archone.catalog.infrastructure.repository;

import com.flowzati.archone.catalog.domain.aggregate.Product;
import com.flowzati.archone.catalog.domain.repository.ProductRepository;
import com.flowzati.archone.catalog.infrastructure.mapper.ProductMapper;
import com.flowzati.archone.catalog.infrastructure.repository.jpa.JpaProductRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class ProductRepositoryImpl implements ProductRepository {

    private final JpaProductRepository repository;

    public ProductRepositoryImpl(JpaProductRepository repository) {
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
