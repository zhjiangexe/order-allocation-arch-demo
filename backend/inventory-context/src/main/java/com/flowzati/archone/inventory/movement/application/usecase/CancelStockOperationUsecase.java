package com.flowzati.archone.inventory.movement.application.usecase;

import com.flowzati.archone.inventory.movement.application.command.CancelStockOperationCommand;
import com.flowzati.archone.inventory.movement.application.command.WarehouseCancellationCheckpoint;
import com.flowzati.archone.inventory.movement.application.port.WarehouseCancellationDecision;
import com.flowzati.archone.inventory.movement.application.port.WarehouseExecutionCancellationCoordinator;
import com.flowzati.archone.inventory.movement.application.result.StockOperationCancellationCheckpoint;
import com.flowzati.archone.inventory.movement.application.result.StockOperationCancellationPreparation;
import com.flowzati.archone.inventory.movement.application.result.StockOperationCancellationStatus;
import com.flowzati.archone.inventory.movement.application.service.StockOperationCancellationTransactions;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationCancellationState;
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
        StockOperationCancellationPreparation preparation =
                transactions.prepare(command.stockOperationId(), command.cancellationOperationId(), clock.instant());
        if (preparation instanceof StockOperationCancellationPreparation.Terminal terminal) {
            return terminal.status();
        }
        StockOperationCancellationCheckpoint checkpoint =
                ((StockOperationCancellationPreparation.Continue) preparation).checkpoint();
        if (checkpoint.state() == StockOperationCancellationState.COMPLETED) {
            return StockOperationCancellationStatus.COMPLETED;
        }
        if (checkpoint.state() == StockOperationCancellationState.EXTERNAL_REJECTED) {
            return StockOperationCancellationStatus.NOT_CANCELLABLE;
        }

        if (checkpoint.state() == StockOperationCancellationState.STARTED) {
            WarehouseCancellationDecision decision = command.warehouseCancellationCheckpoint()
                            == WarehouseCancellationCheckpoint.CONFIRMED
                    ? WarehouseCancellationDecision.CONFIRMED
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
