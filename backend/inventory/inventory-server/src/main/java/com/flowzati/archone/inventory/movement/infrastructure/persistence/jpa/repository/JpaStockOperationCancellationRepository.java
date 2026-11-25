package com.flowzati.archone.inventory.movement.infrastructure.persistence.jpa.repository;

import com.flowzati.archone.inventory.movement.infrastructure.persistence.jpa.model.StockOperationCancellationEntity;
import com.flowzati.archone.inventory.movement.infrastructure.persistence.jpa.model.StockOperationCancellationPrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaStockOperationCancellationRepository
        extends JpaRepository<StockOperationCancellationEntity, StockOperationCancellationPrimaryKey> {}
