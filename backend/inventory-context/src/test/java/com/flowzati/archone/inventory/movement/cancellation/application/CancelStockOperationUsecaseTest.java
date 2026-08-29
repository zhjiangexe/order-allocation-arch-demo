package com.flowzati.archone.inventory.movement.cancellation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.inventory.movement.application.command.CancelStockOperationCommand;
import com.flowzati.archone.inventory.movement.application.port.WarehouseExecutionCancellationCoordinator;
import com.flowzati.archone.inventory.movement.application.port.WarehouseExecutionCancellationCoordinator.Decision;
import com.flowzati.archone.inventory.movement.application.port.WarehouseExecutionCancellationCoordinator.Target;
import com.flowzati.archone.inventory.movement.application.service.StockOperationCancellationTransactions;
import com.flowzati.archone.inventory.movement.application.service.StockOperationCancellationTransactions.Checkpoint;
import com.flowzati.archone.inventory.movement.application.service.StockOperationCancellationTransactions.Preparation;
import com.flowzati.archone.inventory.movement.application.usecase.CancelStockOperationUsecase;
import com.flowzati.archone.inventory.movement.domain.StockOperationCancellationState;
import com.flowzati.archone.inventory.movement.domain.StockOperationCancellationStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CancelStockOperationUsecaseTest {

    private static final Instant NOW = Instant.parse("2026-08-27T08:00:00Z");
    private static final UUID STOCK_OPERATION_ID = new UUID(0, 1);
    private static final UUID OPERATION_ID = new UUID(0, 2);

    private StockOperationCancellationTransactions transactions;
    private WarehouseExecutionCancellationCoordinator coordinator;
    private CancelStockOperationUsecase usecase;

    @BeforeEach
    void setUp() {
        transactions = mock(StockOperationCancellationTransactions.class);
        coordinator = mock(WarehouseExecutionCancellationCoordinator.class);
        usecase = new CancelStockOperationUsecase(transactions, coordinator, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void cancelsConfirmedPickingLocallyWithoutCallingWarehouse() {
        CancelStockOperationCommand command =
                CancelStockOperationCommand.requiringWarehouseConfirmation(STOCK_OPERATION_ID, OPERATION_ID);
        when(transactions.prepare(STOCK_OPERATION_ID, OPERATION_ID, NOW))
                .thenReturn(new Preparation.Terminal(StockOperationCancellationStatus.COMPLETED));

        assertThat(usecase.execute(command)).isEqualTo(StockOperationCancellationStatus.COMPLETED);

        verify(coordinator, never()).cancelExecution(any(), any());
        verify(transactions, never()).complete(any(), any(), any());
    }

    @Test
    void persistsWarehouseConfirmationBeforeLocalCancellation() {
        CancelStockOperationCommand command =
                CancelStockOperationCommand.requiringWarehouseConfirmation(STOCK_OPERATION_ID, OPERATION_ID);
        Target target = new Target(STOCK_OPERATION_ID);
        when(transactions.prepare(STOCK_OPERATION_ID, OPERATION_ID, NOW))
                .thenReturn(new Preparation.Continue(checkpoint(target, StockOperationCancellationState.STARTED)));
        when(coordinator.cancelExecution(target, OPERATION_ID)).thenReturn(Decision.CONFIRMED);
        when(transactions.recordExternalDecision(STOCK_OPERATION_ID, OPERATION_ID, Decision.CONFIRMED, NOW))
                .thenReturn(checkpoint(target, StockOperationCancellationState.EXTERNAL_CONFIRMED));
        when(transactions.complete(STOCK_OPERATION_ID, OPERATION_ID, NOW))
                .thenReturn(StockOperationCancellationStatus.COMPLETED);

        assertThat(usecase.execute(command)).isEqualTo(StockOperationCancellationStatus.COMPLETED);

        verify(coordinator).cancelExecution(target, OPERATION_ID);
        verify(transactions).complete(STOCK_OPERATION_ID, OPERATION_ID, NOW);
    }

    @Test
    void trustedTerminalFactPersistsConfirmationWithoutCallingWarehouseAgain() {
        CancelStockOperationCommand command =
                CancelStockOperationCommand.afterWarehouseConfirmation(STOCK_OPERATION_ID, OPERATION_ID);
        Target target = new Target(STOCK_OPERATION_ID);
        when(transactions.prepare(STOCK_OPERATION_ID, OPERATION_ID, NOW))
                .thenReturn(new Preparation.Continue(checkpoint(target, StockOperationCancellationState.STARTED)));
        when(transactions.recordExternalDecision(STOCK_OPERATION_ID, OPERATION_ID, Decision.CONFIRMED, NOW))
                .thenReturn(checkpoint(target, StockOperationCancellationState.EXTERNAL_CONFIRMED));
        when(transactions.complete(STOCK_OPERATION_ID, OPERATION_ID, NOW))
                .thenReturn(StockOperationCancellationStatus.COMPLETED);

        assertThat(usecase.execute(command)).isEqualTo(StockOperationCancellationStatus.COMPLETED);

        verify(coordinator, never()).cancelExecution(any(), any());
        verify(transactions).complete(STOCK_OPERATION_ID, OPERATION_ID, NOW);
    }

    @Test
    void rejectedWarehouseCancellationDoesNotRunLocalChanges() {
        CancelStockOperationCommand command =
                CancelStockOperationCommand.requiringWarehouseConfirmation(STOCK_OPERATION_ID, OPERATION_ID);
        Target target = new Target(STOCK_OPERATION_ID);
        when(transactions.prepare(STOCK_OPERATION_ID, OPERATION_ID, NOW))
                .thenReturn(new Preparation.Continue(checkpoint(target, StockOperationCancellationState.STARTED)));
        when(coordinator.cancelExecution(target, OPERATION_ID)).thenReturn(Decision.REJECTED);
        when(transactions.recordExternalDecision(STOCK_OPERATION_ID, OPERATION_ID, Decision.REJECTED, NOW))
                .thenReturn(checkpoint(target, StockOperationCancellationState.EXTERNAL_REJECTED));

        assertThat(usecase.execute(command)).isEqualTo(StockOperationCancellationStatus.NOT_CANCELLABLE);

        verify(transactions, never()).complete(any(), any(), any());
    }

    @Test
    void resumesLocalWorkWithoutCallingWarehouseAgain() {
        CancelStockOperationCommand command =
                CancelStockOperationCommand.requiringWarehouseConfirmation(STOCK_OPERATION_ID, OPERATION_ID);
        Target target = new Target(STOCK_OPERATION_ID);
        when(transactions.prepare(STOCK_OPERATION_ID, OPERATION_ID, NOW))
                .thenReturn(new Preparation.Continue(
                        checkpoint(target, StockOperationCancellationState.EXTERNAL_CONFIRMED)));
        when(transactions.complete(STOCK_OPERATION_ID, OPERATION_ID, NOW))
                .thenReturn(StockOperationCancellationStatus.COMPLETED);

        assertThat(usecase.execute(command)).isEqualTo(StockOperationCancellationStatus.COMPLETED);

        verify(coordinator, never()).cancelExecution(any(), any());
    }

    private static Checkpoint checkpoint(Target target, StockOperationCancellationState state) {
        return new Checkpoint(target, state);
    }
}
