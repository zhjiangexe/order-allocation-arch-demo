package com.flowzati.archone.inventory.movement.application.usecase;

import com.flowzati.archone.inventory.movement.application.invocation.CancelStockOperationCommand;
import com.flowzati.archone.inventory.movement.application.result.StockOperationCancellationCheckpoint;
import com.flowzati.archone.inventory.movement.application.result.StockOperationCancellationPreparation;
import com.flowzati.archone.inventory.movement.application.result.StockOperationCancellationStatus;
import com.flowzati.archone.inventory.movement.application.service.StockOperationCancellationTransactions;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationCancellationState;
import java.time.Clock;
import org.springframework.stereotype.Service;

/** Completes an Inventory cancellation after warehouse execution has reached a safe terminal state. */
@Service
public class CancelStockOperationUsecase {

    private final StockOperationCancellationTransactions transactions;
    private final Clock clock;

    public CancelStockOperationUsecase(StockOperationCancellationTransactions transactions, Clock clock) {
        this.transactions = transactions;
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
            checkpoint = transactions.confirmWarehouseCancellation(
                    command.stockOperationId(), command.cancellationOperationId(), clock.instant());
        }
        if (checkpoint.state() == StockOperationCancellationState.EXTERNAL_REJECTED) {
            return StockOperationCancellationStatus.NOT_CANCELLABLE;
        }
        return transactions.complete(command.stockOperationId(), command.cancellationOperationId(), clock.instant());
    }
}
