package com.flowzati.archone.inventory.reservation.assignment.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.flowzati.archone.foundation.time.BusinessClock;
import com.flowzati.archone.inventory.allocation.application.result.StockOperationAssignmentResult;
import com.flowzati.archone.inventory.allocation.application.service.StockOperationAssignmentCoordinator;
import com.flowzati.archone.inventory.allocation.application.state.AssignmentQueueKey;
import com.flowzati.archone.inventory.allocation.application.store.StockOperationAssignmentBacklogStore;
import com.flowzati.archone.inventory.allocation.application.usecase.ReconcileStockOperationBacklogUsecase;
import com.flowzati.archone.inventory.testsupport.InventoryFixtures;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

@DisplayName("Pending operation backlog assignment")
class ReconcileStockOperationBacklogUsecaseTest {

    private static final int MAX_ATTEMPTS_PER_RUN = 5;
    private static final long MAX_RUN_DURATION_MS = 45_000;
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 27);
    private static final UUID OWNER_ID = uuid(1);
    private static final UUID LOCATION_ID = uuid(2);

    @Test
    @DisplayName("successful queues advance in fair rounds under one global attempt budget")
    void advancesSuccessfulQueuesInRoundRobinOrder() {
        StockOperationAssignmentBacklogStore stockOperationAssignmentBacklogStore =
                mock(StockOperationAssignmentBacklogStore.class);
        StockOperationAssignmentCoordinator coordinator = mock(StockOperationAssignmentCoordinator.class);
        AssignmentQueueKey first = queueKey("SKU-1");
        AssignmentQueueKey second = queueKey("SKU-2");
        when(stockOperationAssignmentBacklogStore.findQueueKeysWithAvailableStock(TODAY, MAX_ATTEMPTS_PER_RUN))
                .thenReturn(List.of(first, second));
        when(coordinator.tryAssignNext(first)).thenReturn(Optional.of(mock(StockOperationAssignmentResult.class)));
        when(coordinator.tryAssignNext(second)).thenReturn(Optional.of(mock(StockOperationAssignmentResult.class)));

        new ReconcileStockOperationBacklogUsecase(
                        stockOperationAssignmentBacklogStore,
                        coordinator,
                        appClock(),
                        MAX_ATTEMPTS_PER_RUN,
                        MAX_RUN_DURATION_MS)
                .execute();

        InOrder order = inOrder(coordinator);
        order.verify(coordinator).tryAssignNext(first);
        order.verify(coordinator).tryAssignNext(second);
        order.verify(coordinator).tryAssignNext(first);
        order.verify(coordinator).tryAssignNext(second);
        order.verify(coordinator).tryAssignNext(first);
        verifyNoMoreInteractions(coordinator);
    }

    @Test
    @DisplayName("a queue without an assignment is deferred while following queues continue")
    void defersInactiveQueueAndContinues() {
        StockOperationAssignmentBacklogStore stockOperationAssignmentBacklogStore =
                mock(StockOperationAssignmentBacklogStore.class);
        StockOperationAssignmentCoordinator coordinator = mock(StockOperationAssignmentCoordinator.class);
        AssignmentQueueKey first = queueKey("SKU-1");
        AssignmentQueueKey second = queueKey("SKU-2");
        when(stockOperationAssignmentBacklogStore.findQueueKeysWithAvailableStock(TODAY, MAX_ATTEMPTS_PER_RUN))
                .thenReturn(List.of(first, second));
        when(coordinator.tryAssignNext(first)).thenReturn(Optional.empty());
        when(coordinator.tryAssignNext(second)).thenReturn(Optional.empty());

        new ReconcileStockOperationBacklogUsecase(
                        stockOperationAssignmentBacklogStore,
                        coordinator,
                        appClock(),
                        MAX_ATTEMPTS_PER_RUN,
                        MAX_RUN_DURATION_MS)
                .execute();

        verify(coordinator, times(1)).tryAssignNext(first);
        verify(coordinator, times(1)).tryAssignNext(second);
        verifyNoMoreInteractions(coordinator);
    }

    @Test
    @DisplayName("one queue failure is isolated and following queues continue")
    void isolatesQueueFailure() {
        StockOperationAssignmentBacklogStore stockOperationAssignmentBacklogStore =
                mock(StockOperationAssignmentBacklogStore.class);
        StockOperationAssignmentCoordinator coordinator = mock(StockOperationAssignmentCoordinator.class);
        AssignmentQueueKey failed = queueKey("SKU-1");
        AssignmentQueueKey following = queueKey("SKU-2");
        when(stockOperationAssignmentBacklogStore.findQueueKeysWithAvailableStock(TODAY, MAX_ATTEMPTS_PER_RUN))
                .thenReturn(List.of(failed, following));
        when(coordinator.tryAssignNext(failed)).thenThrow(new IllegalStateException("unexpected"));
        when(coordinator.tryAssignNext(following)).thenReturn(Optional.empty());

        new ReconcileStockOperationBacklogUsecase(
                        stockOperationAssignmentBacklogStore,
                        coordinator,
                        appClock(),
                        MAX_ATTEMPTS_PER_RUN,
                        MAX_RUN_DURATION_MS)
                .execute();

        InOrder order = inOrder(coordinator);
        order.verify(coordinator).tryAssignNext(failed);
        order.verify(coordinator).tryAssignNext(following);
        verifyNoMoreInteractions(coordinator);
    }

    @Test
    @DisplayName("queue discovery failure aborts the reconciliation run")
    void propagatesQueueDiscoveryFailure() {
        StockOperationAssignmentBacklogStore stockOperationAssignmentBacklogStore =
                mock(StockOperationAssignmentBacklogStore.class);
        StockOperationAssignmentCoordinator coordinator = mock(StockOperationAssignmentCoordinator.class);
        IllegalStateException failure = new IllegalStateException("scan failed");
        when(stockOperationAssignmentBacklogStore.findQueueKeysWithAvailableStock(TODAY, MAX_ATTEMPTS_PER_RUN))
                .thenThrow(failure);
        var usecase = new ReconcileStockOperationBacklogUsecase(
                stockOperationAssignmentBacklogStore,
                coordinator,
                appClock(),
                MAX_ATTEMPTS_PER_RUN,
                MAX_RUN_DURATION_MS);

        assertThatThrownBy(usecase::execute).isSameAs(failure);
        verifyNoMoreInteractions(coordinator);
    }

    private static AssignmentQueueKey queueKey(String skuCode) {
        return new AssignmentQueueKey(OWNER_ID, LOCATION_ID, skuCode);
    }

    private static BusinessClock appClock() {
        return InventoryFixtures.businessClock(
                Clock.fixed(Instant.parse("2026-08-26T16:00:00Z"), ZoneId.of("UTC")), "Asia/Taipei");
    }

    private static UUID uuid(int seed) {
        return UUID.fromString(String.format("00000000-0000-7000-8000-%012d", seed));
    }
}
