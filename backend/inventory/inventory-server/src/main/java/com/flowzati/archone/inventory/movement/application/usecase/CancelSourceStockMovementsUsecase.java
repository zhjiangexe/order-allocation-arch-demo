package com.flowzati.archone.inventory.movement.application.usecase;

import com.flowzati.archone.inventory.movement.application.invocation.CancelSourceStockMovementsCommand;
import com.flowzati.archone.inventory.movement.application.invocation.CancelStockOperationCommand;
import com.flowzati.archone.inventory.movement.application.result.StockOperationCancellationStatus;
import com.flowzati.archone.inventory.movement.application.store.StockOperationStore;
import org.springframework.stereotype.Service;

/** Resolves a source document identity once, then delegates to the operation-targeted lifecycle. */
@Service
public class CancelSourceStockMovementsUsecase {

    private final StockOperationStore stockOperationStore;
    private final CancelStockOperationUsecase cancelStockOperation;

    public CancelSourceStockMovementsUsecase(
            StockOperationStore stockOperationStore, CancelStockOperationUsecase cancelStockOperation) {
        this.stockOperationStore = stockOperationStore;
        this.cancelStockOperation = cancelStockOperation;
    }

    public StockOperationCancellationStatus execute(CancelSourceStockMovementsCommand command) {
        return stockOperationStore
                .findBySource(command.source())
                .map(operation -> cancelStockOperation.execute(
                        new CancelStockOperationCommand(operation.id(), command.cancellationOperationId())))
                .orElse(StockOperationCancellationStatus.COMPLETED);
    }
}
