package com.flowzati.archone.inventory.movement.infrastructure.repo;

import com.flowzati.archone.inventory.movement.domain.MovementSourceType;
import com.flowzati.archone.inventory.movement.infrastructure.entity.StockOperationEntity;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Aggregate persistence only; operational reads live behind dedicated projection repositories. */
public interface JpaStockOperationRepository extends JpaRepository<StockOperationEntity, UUID> {

    List<StockOperationEntity> findByIdIn(Collection<UUID> ids);

    Optional<StockOperationEntity> findBySourceTypeAndSourceIdAndAllocationUnitKey(
            MovementSourceType sourceType, String sourceId, String allocationUnitKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select operation from StockOperationEntity operation where operation.id = :stockOperationId")
    Optional<StockOperationEntity> findLockedById(@Param("stockOperationId") UUID stockOperationId);
}
