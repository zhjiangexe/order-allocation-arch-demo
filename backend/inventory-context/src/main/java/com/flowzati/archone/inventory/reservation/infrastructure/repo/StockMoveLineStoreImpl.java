package com.flowzati.archone.inventory.reservation.infrastructure.repo;

import com.flowzati.archone.inventory.reservation.application.repo.StockMoveLineStore;
import com.flowzati.archone.inventory.reservation.domain.StockMoveLine;
import com.flowzati.archone.inventory.reservation.infrastructure.mapper.StockMoveLineMapper;
import com.flowzati.archone.inventory.reservation.infrastructure.repo.jpa.JpaStockMoveLineRepository;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class StockMoveLineStoreImpl implements StockMoveLineStore {

    private final JpaStockMoveLineRepository jpaStockMoveLineRepository;

    public StockMoveLineStoreImpl(JpaStockMoveLineRepository jpaStockMoveLineRepository) {
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
