package com.flowzati.archone.inventory.movement.infrastructure.repo;

import com.flowzati.archone.inventory.movement.infrastructure.entity.StockMoveEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaStockMoveRepository extends JpaRepository<StockMoveEntity, UUID> {

    List<StockMoveEntity> findByStockOperationIdOrderByLineSequenceAscIdAsc(UUID stockOperationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select move
              from StockMoveEntity move
             where move.stockOperationId = :stockOperationId
             order by move.id
            """)
    List<StockMoveEntity> findLockedByStockOperationIdOrderByIdAsc(@Param("stockOperationId") UUID stockOperationId);
}
