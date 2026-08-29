package com.flowzati.archone.inventory.movement.application.usecase;

import com.flowzati.archone.inventory.movement.application.command.CancelStockOperationCommand;
import com.flowzati.archone.inventory.movement.application.port.WarehouseExecutionCancellationCoordinator;
import com.flowzati.archone.inventory.movement.application.port.WarehouseExecutionCancellationCoordinator.Decision;
import com.flowzati.archone.inventory.movement.application.service.StockOperationCancellationTransactions;
import com.flowzati.archone.inventory.movement.application.service.StockOperationCancellationTransactions.Checkpoint;
import com.flowzati.archone.inventory.movement.application.service.StockOperationCancellationTransactions.Preparation;
import com.flowzati.archone.inventory.movement.domain.StockOperationCancellationState;
import com.flowzati.archone.inventory.movement.domain.StockOperationCancellationStatus;
import com.flowzati.archone.inventory.movement.domain.WarehouseCancellationCheckpoint;
import java.time.Clock;
import org.springframework.stereotype.Service;

/** Coordinates an assigned operation cancellation without holding Inventory locks during the WMS call. */
@Service
public class CancelStockOperationUsecase {

    private final StockOperationCancellationTransactions transactions;
    private final WarehouseExecutionCancellationCoordinator warehouseCoordinator;
    private final Clock clock;

    public CancelStockOperationUsecase(
            StockOperationCancellationTransactions transactions,
            WarehouseExecutionCancellationCoordinator warehouseCoordinator,
            Clock clock) {
        this.transactions = transactions;
        this.warehouseCoordinator = warehouseCoordinator;
        this.clock = clock;
    }

    public StockOperationCancellationStatus execute(CancelStockOperationCommand command) {
        Preparation preparation =
                transactions.prepare(command.stockOperationId(), command.cancellationOperationId(), clock.instant());
        if (preparation instanceof Preparation.Terminal terminal) {
            return terminal.status();
        }
        Checkpoint checkpoint = ((Preparation.Continue) preparation).checkpoint();
        if (checkpoint.state() == StockOperationCancellationState.COMPLETED) {
            return StockOperationCancellationStatus.COMPLETED;
        }
        if (checkpoint.state() == StockOperationCancellationState.EXTERNAL_REJECTED) {
            return StockOperationCancellationStatus.NOT_CANCELLABLE;
        }

        if (checkpoint.state() == StockOperationCancellationState.STARTED) {
            Decision decision = command.warehouseCancellationCheckpoint() == WarehouseCancellationCheckpoint.CONFIRMED
                    ? Decision.CONFIRMED
                    : warehouseCoordinator.cancelExecution(checkpoint.target(), command.cancellationOperationId());
            checkpoint = transactions.recordExternalDecision(
                    command.stockOperationId(), command.cancellationOperationId(), decision, clock.instant());
        }
        if (checkpoint.state() == StockOperationCancellationState.EXTERNAL_REJECTED) {
            return StockOperationCancellationStatus.NOT_CANCELLABLE;
        }
        return transactions.complete(command.stockOperationId(), command.cancellationOperationId(), clock.instant());
    }
}
