package com.flowzati.archone.inventory.balance.infrastructure.persistence.jpa.repository;

import com.flowzati.archone.inventory.balance.infrastructure.persistence.jpa.entity.StockQuantEntity;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaStockQuantRepository extends JpaRepository<StockQuantEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
      SELECT b FROM StockQuantEntity b
      WHERE b.id IN :ids
      ORDER BY b.skuCode ASC, b.expiryDate ASC, b.inDate ASC, b.id ASC
      """)
    List<StockQuantEntity> findAllByIdInGlobalWriteOrderForUpdate(@Param("ids") Collection<UUID> ids);

    Optional<StockQuantEntity> findByOwnerIdAndLocationIdAndSkuCodeAndInDateAndExpiryDate(
            UUID ownerId, UUID locationId, String skuCode, LocalDate inDate, LocalDate expiryDate);
}
