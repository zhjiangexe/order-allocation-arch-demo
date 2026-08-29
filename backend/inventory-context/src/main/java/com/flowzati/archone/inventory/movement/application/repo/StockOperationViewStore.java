package com.flowzati.archone.inventory.movement.application.repo;

import com.flowzati.archone.inventory.movement.application.StockOperationView;
import com.flowzati.archone.inventory.movement.domain.StockOperationSource;
import java.util.List;
import java.util.Optional;

/** Bounded projection of operations, moves, move lines and referenced quants. */
public interface StockOperationViewStore {

    List<StockOperationView> findConfirmedOutbound(int limit);

    Optional<StockOperationView> findBySource(StockOperationSource source);
}
