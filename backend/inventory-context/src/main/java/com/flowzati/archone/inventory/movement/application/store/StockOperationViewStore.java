package com.flowzati.archone.inventory.movement.application.store;

import com.flowzati.archone.inventory.movement.application.result.StockOperationView;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationSource;
import java.util.List;
import java.util.Optional;

/** Bounded projection of operations, moves, move lines and referenced quants. */
public interface StockOperationViewStore {

    List<StockOperationView> findConfirmedOutbound(int limit);

    Optional<StockOperationView> findBySource(StockOperationSource source);
}
