package com.flowzati.archone.inventory.position.infrastructure.repo;

import com.flowzati.archone.inventory.position.application.store.StockQuantStore;
import com.flowzati.archone.inventory.position.domain.StockQuant;
import com.flowzati.archone.inventory.position.infrastructure.mapper.StockQuantMapper;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class StockQuantStoreImpl implements StockQuantStore {

    private final JpaStockQuantRepository jpaStockQuantRepository;

    public StockQuantStoreImpl(JpaStockQuantRepository jpaStockQuantRepository) {
        this.jpaStockQuantRepository = jpaStockQuantRepository;
    }

    @Override
    public Optional<StockQuant> findById(UUID id) {
        return jpaStockQuantRepository.findById(id).map(StockQuantMapper::toDomain);
    }

    @Override
    public List<StockQuant> findByIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jpaStockQuantRepository.findAllById(ids).stream()
                .map(StockQuantMapper::toDomain)
                .toList();
    }

    @Override
    public List<StockQuant> lockByIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jpaStockQuantRepository.findAllByIdInGlobalWriteOrderForUpdate(ids).stream()
                .map(StockQuantMapper::toDomain)
                .toList();
    }

    @Override
    public Optional<StockQuant> findByIdentity(
            UUID ownerId, UUID locationId, String skuCode, LocalDate inDate, LocalDate expiryDate) {
        return jpaStockQuantRepository
                .findByOwnerIdAndLocationIdAndSkuCodeAndInDateAndExpiryDate(
                        ownerId, locationId, skuCode, inDate, expiryDate)
                .map(StockQuantMapper::toDomain);
    }

    @Override
    public int save(StockQuant stockQuant) {
        jpaStockQuantRepository.save(StockQuantMapper.toEntity(stockQuant));
        return 1;
    }
}
