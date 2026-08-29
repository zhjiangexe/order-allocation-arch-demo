package com.flowzati.archone.inventory.movement.infrastructure.repo;

import com.flowzati.archone.inventory.movement.infrastructure.StockOperationCancellationKey;
import com.flowzati.archone.inventory.movement.infrastructure.entity.StockOperationCancellationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaStockOperationCancellationRepository
        extends JpaRepository<StockOperationCancellationEntity, StockOperationCancellationKey> {}
