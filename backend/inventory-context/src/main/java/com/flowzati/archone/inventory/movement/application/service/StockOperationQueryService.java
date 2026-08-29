package com.flowzati.archone.inventory.movement.application.service;

import com.flowzati.archone.inventory.movement.application.StockOperationView;
import com.flowzati.archone.inventory.movement.application.repo.StockOperationViewStore;
import com.flowzati.archone.inventory.movement.domain.StockOperationSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Thin application reader over the bounded stock-operation projection. */
@Service
@Transactional(readOnly = true)
public class StockOperationQueryService {

    private final StockOperationViewStore stockOperationViewStore;

    public StockOperationQueryService(StockOperationViewStore stockOperationViewStore) {
        this.stockOperationViewStore = stockOperationViewStore;
    }

    public List<StockOperationView> listConfirmed(int limit) {
        return stockOperationViewStore.findConfirmedOutbound(limit);
    }

    public Optional<StockOperationView> findPrimaryOrder(UUID orderId) {
        if (orderId == null) {
            throw new IllegalArgumentException("Order ID is required");
        }
        return find(StockOperationSource.primaryOrder(orderId.toString()));
    }

    public Optional<StockOperationView> find(StockOperationSource source) {
        return stockOperationViewStore.findBySource(source);
    }
}
