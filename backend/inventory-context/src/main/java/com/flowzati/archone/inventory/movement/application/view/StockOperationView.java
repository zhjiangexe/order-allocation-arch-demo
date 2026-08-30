package com.flowzati.archone.inventory.movement.application.view;

import java.util.List;

/** Source declaration, warehouse operation group and physical reservation detail in one read-only view. */
public record StockOperationView(
        StockOperationSourceView source, StockOperationHeaderView operation, List<StockMoveView> moves) {

    public StockOperationView {
        moves = List.copyOf(moves);
    }
}
