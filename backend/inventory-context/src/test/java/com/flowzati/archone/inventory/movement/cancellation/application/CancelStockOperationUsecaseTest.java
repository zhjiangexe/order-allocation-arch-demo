package com.flowzati.archone.inventory.movement.cancellation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.inventory.movement.application.command.CancelStockOperationCommand;
import com.flowzati.archone.inventory.movement.application.port.WarehouseCancellationDecision;
import com.flowzati.archone.inventory.movement.application.port.WarehouseCancellationTarget;
import com.flowzati.archone.inventory.movement.application.port.WarehouseExecutionCancellationCoordinator;
import com.flowzati.archone.inventory.movement.application.result.StockOperationCancellationCheckpoint;
import com.flowzati.archone.inventory.movement.application.result.StockOperationCancellationPreparation;
import com.flowzati.archone.inventory.movement.application.result.StockOperationCancellationStatus;
import com.flowzati.archone.inventory.movement.application.service.StockOperationCancellationTransactions;
import com.flowzati.archone.inventory.movement.application.usecase.CancelStockOperationUsecase;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationCancellationState;
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
                .thenReturn(
                        new StockOperationCancellationPreparation.Terminal(StockOperationCancellationStatus.COMPLETED));

        assertThat(usecase.execute(command)).isEqualTo(StockOperationCancellationStatus.COMPLETED);

        verify(coordinator, never()).cancelExecution(any(), any());
        verify(transactions, never()).complete(any(), any(), any());
    }

    @Test
    void persistsWarehouseConfirmationBeforeLocalCancellation() {
        CancelStockOperationCommand command =
                CancelStockOperationCommand.requiringWarehouseConfirmation(STOCK_OPERATION_ID, OPERATION_ID);
        WarehouseCancellationTarget target = new WarehouseCancellationTarget(STOCK_OPERATION_ID);
        when(transactions.prepare(STOCK_OPERATION_ID, OPERATION_ID, NOW))
                .thenReturn(new StockOperationCancellationPreparation.Continue(
                        checkpoint(target, StockOperationCancellationState.STARTED)));
        when(coordinator.cancelExecution(target, OPERATION_ID)).thenReturn(WarehouseCancellationDecision.CONFIRMED);
        when(transactions.recordExternalDecision(
                        STOCK_OPERATION_ID, OPERATION_ID, WarehouseCancellationDecision.CONFIRMED, NOW))
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
        WarehouseCancellationTarget target = new WarehouseCancellationTarget(STOCK_OPERATION_ID);
        when(transactions.prepare(STOCK_OPERATION_ID, OPERATION_ID, NOW))
                .thenReturn(new StockOperationCancellationPreparation.Continue(
                        checkpoint(target, StockOperationCancellationState.STARTED)));
        when(transactions.recordExternalDecision(
                        STOCK_OPERATION_ID, OPERATION_ID, WarehouseCancellationDecision.CONFIRMED, NOW))
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
        WarehouseCancellationTarget target = new WarehouseCancellationTarget(STOCK_OPERATION_ID);
        when(transactions.prepare(STOCK_OPERATION_ID, OPERATION_ID, NOW))
                .thenReturn(new StockOperationCancellationPreparation.Continue(
                        checkpoint(target, StockOperationCancellationState.STARTED)));
        when(coordinator.cancelExecution(target, OPERATION_ID)).thenReturn(WarehouseCancellationDecision.REJECTED);
        when(transactions.recordExternalDecision(
                        STOCK_OPERATION_ID, OPERATION_ID, WarehouseCancellationDecision.REJECTED, NOW))
                .thenReturn(checkpoint(target, StockOperationCancellationState.EXTERNAL_REJECTED));

        assertThat(usecase.execute(command)).isEqualTo(StockOperationCancellationStatus.NOT_CANCELLABLE);

        verify(transactions, never()).complete(any(), any(), any());
    }

    @Test
    void resumesLocalWorkWithoutCallingWarehouseAgain() {
        CancelStockOperationCommand command =
                CancelStockOperationCommand.requiringWarehouseConfirmation(STOCK_OPERATION_ID, OPERATION_ID);
        WarehouseCancellationTarget target = new WarehouseCancellationTarget(STOCK_OPERATION_ID);
        when(transactions.prepare(STOCK_OPERATION_ID, OPERATION_ID, NOW))
                .thenReturn(new StockOperationCancellationPreparation.Continue(
                        checkpoint(target, StockOperationCancellationState.EXTERNAL_CONFIRMED)));
        when(transactions.complete(STOCK_OPERATION_ID, OPERATION_ID, NOW))
                .thenReturn(StockOperationCancellationStatus.COMPLETED);

        assertThat(usecase.execute(command)).isEqualTo(StockOperationCancellationStatus.COMPLETED);

        verify(coordinator, never()).cancelExecution(any(), any());
    }

    private static StockOperationCancellationCheckpoint checkpoint(
            WarehouseCancellationTarget target, StockOperationCancellationState state) {
        return new StockOperationCancellationCheckpoint(target, state);
    }
}
