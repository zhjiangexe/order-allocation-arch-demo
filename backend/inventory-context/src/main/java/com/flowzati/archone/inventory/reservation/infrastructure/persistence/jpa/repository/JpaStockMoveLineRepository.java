package com.flowzati.archone.inventory.reservation.infrastructure.persistence.jpa.repository;

import com.flowzati.archone.inventory.reservation.infrastructure.persistence.jpa.entity.StockMoveLineEntity;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaStockMoveLineRepository extends JpaRepository<StockMoveLineEntity, UUID> {

    List<StockMoveLineEntity> findByMoveIdIn(Collection<UUID> moveIds);

    /** 釋放是刪除，不是標記——一條被釋放的明細不表達任何事實。 */
    void deleteByMoveIdIn(Collection<UUID> moveIds);
}
