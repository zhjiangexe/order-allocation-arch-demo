package com.flowzati.archone.inventory.movement.infrastructure.persistence.jpa.store;

import com.flowzati.archone.inventory.movement.application.store.StockMoveStore;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import com.flowzati.archone.inventory.movement.infrastructure.persistence.jpa.mapper.StockMoveMapper;
import com.flowzati.archone.inventory.movement.infrastructure.persistence.jpa.repository.JpaStockMoveRepository;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class StockMoveStoreAdapter implements StockMoveStore {

    private final JpaStockMoveRepository jpaStockMoveRepository;

    public StockMoveStoreAdapter(JpaStockMoveRepository jpaStockMoveRepository) {
        this.jpaStockMoveRepository = jpaStockMoveRepository;
    }

    @Override
    public void save(StockMove move) {
        jpaStockMoveRepository.save(StockMoveMapper.toEntity(move));
    }

    @Override
    public List<StockMove> saveAll(Collection<StockMove> moves) {
        // 以 id 排序寫入，與庫存列的全序同一個判準：同一批交易若以不同順序碰同一組列，併發下
        // 就有死鎖的機會。搬運的爭用遠低於庫存，但一致的順序不花任何成本。
        //
        // 回傳寫入後的樣子（帶版號），呼叫端才有辦法在同一個交易裡對同一列寫第二次。
        var entities = moves.stream()
                .sorted(Comparator.comparing(StockMove::getId))
                .map(StockMoveMapper::toEntity)
                .map(jpaStockMoveRepository::save)
                .toList();
        // Candidate and backlog projections use JDBC in the same transaction. Flush the complete registration
        // batch so those projections observe both the operation and every canonical move.
        jpaStockMoveRepository.flush();
        return entities.stream().map(StockMoveMapper::toDomain).toList();
    }

    @Override
    public List<StockMove> findOrderedByStockOperationId(UUID stockOperationId) {
        return jpaStockMoveRepository.findByStockOperationIdOrderByLineSequenceAscIdAsc(stockOperationId).stream()
                .map(StockMoveMapper::toDomain)
                .toList();
    }

    @Override
    public List<StockMove> lockByStockOperationIdInIdOrder(UUID stockOperationId) {
        return jpaStockMoveRepository.findLockedByStockOperationIdOrderByIdAsc(stockOperationId).stream()
                .map(StockMoveMapper::toDomain)
                .toList();
    }

    @Override
    public List<StockMove> findByIds(Collection<UUID> moveIds) {
        if (moveIds.isEmpty()) {
            return List.of();
        }
        return jpaStockMoveRepository.findAllById(moveIds).stream()
                .map(StockMoveMapper::toDomain)
                .sorted(Comparator.comparing(StockMove::getId))
                .toList();
    }
}
