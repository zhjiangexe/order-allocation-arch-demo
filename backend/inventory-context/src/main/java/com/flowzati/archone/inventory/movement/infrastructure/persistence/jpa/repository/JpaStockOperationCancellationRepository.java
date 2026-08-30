package com.flowzati.archone.inventory.movement.infrastructure.persistence.jpa.repository;

import com.flowzati.archone.inventory.movement.infrastructure.persistence.jpa.entity.StockOperationCancellationEntity;
import com.flowzati.archone.inventory.movement.infrastructure.persistence.jpa.entity.StockOperationCancellationKey;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaStockOperationCancellationRepository
        extends JpaRepository<StockOperationCancellationEntity, StockOperationCancellationKey> {}
