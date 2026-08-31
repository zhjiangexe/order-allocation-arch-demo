package com.flowzati.archone.inventory.movement.cancellation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.inventory.movement.application.command.CancelStockOperationCommand;
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
    private CancelStockOperationUsecase usecase;

    @BeforeEach
    void setUp() {
        transactions = mock(StockOperationCancellationTransactions.class);
        usecase = new CancelStockOperationUsecase(transactions, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void returnsTerminalPreparationWithoutCompletingAgain() {
        CancelStockOperationCommand command = new CancelStockOperationCommand(STOCK_OPERATION_ID, OPERATION_ID);
        when(transactions.prepare(STOCK_OPERATION_ID, OPERATION_ID, NOW))
                .thenReturn(
                        new StockOperationCancellationPreparation.Terminal(StockOperationCancellationStatus.COMPLETED));

        assertThat(usecase.execute(command)).isEqualTo(StockOperationCancellationStatus.COMPLETED);

        verify(transactions, never()).confirmWarehouseCancellation(any(), any(), any());
        verify(transactions, never()).complete(any(), any(), any());
    }

    @Test
    void persistsTrustedWarehouseConfirmationBeforeLocalCancellation() {
        CancelStockOperationCommand command = new CancelStockOperationCommand(STOCK_OPERATION_ID, OPERATION_ID);
        when(transactions.prepare(STOCK_OPERATION_ID, OPERATION_ID, NOW))
                .thenReturn(new StockOperationCancellationPreparation.Continue(
                        checkpoint(StockOperationCancellationState.STARTED)));
        when(transactions.confirmWarehouseCancellation(STOCK_OPERATION_ID, OPERATION_ID, NOW))
                .thenReturn(checkpoint(StockOperationCancellationState.EXTERNAL_CONFIRMED));
        when(transactions.complete(STOCK_OPERATION_ID, OPERATION_ID, NOW))
                .thenReturn(StockOperationCancellationStatus.COMPLETED);

        assertThat(usecase.execute(command)).isEqualTo(StockOperationCancellationStatus.COMPLETED);

        verify(transactions).confirmWarehouseCancellation(STOCK_OPERATION_ID, OPERATION_ID, NOW);
        verify(transactions).complete(STOCK_OPERATION_ID, OPERATION_ID, NOW);
    }

    @Test
    void persistedRejectionDoesNotRunLocalChanges() {
        CancelStockOperationCommand command = new CancelStockOperationCommand(STOCK_OPERATION_ID, OPERATION_ID);
        when(transactions.prepare(STOCK_OPERATION_ID, OPERATION_ID, NOW))
                .thenReturn(new StockOperationCancellationPreparation.Continue(
                        checkpoint(StockOperationCancellationState.EXTERNAL_REJECTED)));

        assertThat(usecase.execute(command)).isEqualTo(StockOperationCancellationStatus.NOT_CANCELLABLE);

        verify(transactions, never()).confirmWarehouseCancellation(any(), any(), any());
        verify(transactions, never()).complete(any(), any(), any());
    }

    @Test
    void resumesLocalWorkFromPersistedConfirmation() {
        CancelStockOperationCommand command = new CancelStockOperationCommand(STOCK_OPERATION_ID, OPERATION_ID);
        when(transactions.prepare(STOCK_OPERATION_ID, OPERATION_ID, NOW))
                .thenReturn(new StockOperationCancellationPreparation.Continue(
                        checkpoint(StockOperationCancellationState.EXTERNAL_CONFIRMED)));
        when(transactions.complete(STOCK_OPERATION_ID, OPERATION_ID, NOW))
                .thenReturn(StockOperationCancellationStatus.COMPLETED);

        assertThat(usecase.execute(command)).isEqualTo(StockOperationCancellationStatus.COMPLETED);

        verify(transactions, never()).confirmWarehouseCancellation(any(), any(), any());
        verify(transactions).complete(STOCK_OPERATION_ID, OPERATION_ID, NOW);
    }

    private static StockOperationCancellationCheckpoint checkpoint(StockOperationCancellationState state) {
        return new StockOperationCancellationCheckpoint(state);
    }
}
