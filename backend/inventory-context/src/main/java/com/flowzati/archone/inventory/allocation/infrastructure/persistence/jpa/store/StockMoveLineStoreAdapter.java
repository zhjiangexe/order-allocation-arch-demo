package com.flowzati.archone.inventory.allocation.infrastructure.persistence.jpa.store;

import com.flowzati.archone.inventory.allocation.application.store.StockMoveLineStore;
import com.flowzati.archone.inventory.allocation.domain.entity.StockMoveLine;
import com.flowzati.archone.inventory.allocation.infrastructure.persistence.jpa.mapper.StockMoveLineMapper;
import com.flowzati.archone.inventory.allocation.infrastructure.persistence.jpa.repository.JpaStockMoveLineRepository;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class StockMoveLineStoreAdapter implements StockMoveLineStore {

    private final JpaStockMoveLineRepository jpaStockMoveLineRepository;

    public StockMoveLineStoreAdapter(JpaStockMoveLineRepository jpaStockMoveLineRepository) {
        this.jpaStockMoveLineRepository = jpaStockMoveLineRepository;
    }

    @Override
    public void saveAll(Collection<StockMoveLine> stockMoveLines) {
        stockMoveLines.stream()
                .sorted(Comparator.comparing(StockMoveLine::id))
                .map(StockMoveLineMapper::toEntity)
                .forEach(jpaStockMoveLineRepository::save);
    }

    @Override
    public List<StockMoveLine> findByMoveIds(Collection<UUID> stockMoveIds) {
        if (stockMoveIds.isEmpty()) {
            return List.of();
        }
        return jpaStockMoveLineRepository.findByMoveIdIn(stockMoveIds).stream()
                .map(StockMoveLineMapper::toDomain)
                .toList();
    }

    @Override
    public void deleteByMoveIds(Collection<UUID> stockMoveIds) {
        if (stockMoveIds.isEmpty()) {
            return;
        }
        jpaStockMoveLineRepository.deleteByMoveIdIn(stockMoveIds);
    }
}
