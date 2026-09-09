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
import com.flowzati.archone.inventory.allocation.application.state.AssignmentQueueKey;
import com.flowzati.archone.inventory.allocation.application.store.StockOperationAssignmentBacklogStore;
import com.flowzati.archone.inventory.allocation.application.usecase.AssignNextStockOperationUsecase;
import com.flowzati.archone.inventory.allocation.application.usecase.StockOperationBacklogReconciler;
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

@DisplayName("Pending operation backlog assignment")
class StockOperationBacklogReconcilerTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 27);
    private static final UUID OWNER_ID = uuid(1);
    private static final UUID LOCATION_ID = uuid(2);
    private final StockOperationAssignmentBacklogStore backlog = mock(StockOperationAssignmentBacklogStore.class);
    private final AssignNextStockOperationUsecase assignNextStockOperationUsecase =
            mock(AssignNextStockOperationUsecase.class);
    private final StockOperationBacklogReconciler usecase =
            new StockOperationBacklogReconciler(backlog, assignNextStockOperationUsecase, appClock());

    @Test
    void drainsSuccessfulQueuesInFairRounds() {
        var first = queueKey("SKU-1");
        var second = queueKey("SKU-2");
        when(backlog.findQueueKeysWithAvailableStock(TODAY, 200, null)).thenReturn(List.of(first, second));
        when(assignNextStockOperationUsecase.execute(first))
                .thenReturn(Optional.of(mock(StockOperationAssignmentResult.class)), Optional.empty());
        when(assignNextStockOperationUsecase.execute(second))
                .thenReturn(
                        Optional.of(mock(StockOperationAssignmentResult.class)),
                        Optional.of(mock(StockOperationAssignmentResult.class)),
                        Optional.empty());
        usecase.execute();
        var order = inOrder(assignNextStockOperationUsecase);
        order.verify(assignNextStockOperationUsecase).execute(first);
        order.verify(assignNextStockOperationUsecase).execute(second);
        order.verify(assignNextStockOperationUsecase).execute(first);
        order.verify(assignNextStockOperationUsecase, times(2)).execute(second);
        verify(backlog).findQueueKeysWithAvailableStock(TODAY, 200, second);
        verifyNoMoreInteractions(assignNextStockOperationUsecase);
    }

    @Test
    void scansPastTwoHundredShortageQueuesWithinOneRunAndStartsFreshNextRun() {
        var shortages = java.util.stream.IntStream.range(0, 200)
                .mapToObj(i -> queueKey(String.format("SKU-%03d", i)))
                .toList();
        var ready = queueKey("SKU-200");
        when(backlog.findQueueKeysWithAvailableStock(TODAY, 200, null)).thenReturn(shortages);
        when(backlog.findQueueKeysWithAvailableStock(TODAY, 200, shortages.getLast()))
                .thenReturn(List.of(ready));
        when(assignNextStockOperationUsecase.execute(ready))
                .thenReturn(Optional.of(mock(StockOperationAssignmentResult.class)), Optional.empty());
        usecase.execute();
        verify(assignNextStockOperationUsecase, times(2)).execute(ready);
        for (var queue : shortages) {
            verify(assignNextStockOperationUsecase).execute(queue);
        }
        usecase.execute();
        verify(backlog, times(2)).findQueueKeysWithAvailableStock(TODAY, 200, null);
        verify(assignNextStockOperationUsecase, times(3)).execute(ready);
    }

    @Test
    void isolatesQueueFailureAndContinuesToNextPage() {
        var failed = queueKey("SKU-1");
        var following = queueKey("SKU-2");
        when(backlog.findQueueKeysWithAvailableStock(TODAY, 200, null)).thenReturn(List.of(failed));
        when(backlog.findQueueKeysWithAvailableStock(TODAY, 200, failed)).thenReturn(List.of(following));
        when(assignNextStockOperationUsecase.execute(failed)).thenThrow(new IllegalStateException("unexpected"));
        usecase.execute();
        var order = inOrder(assignNextStockOperationUsecase);
        order.verify(assignNextStockOperationUsecase).execute(failed);
        order.verify(assignNextStockOperationUsecase).execute(following);
        verifyNoMoreInteractions(assignNextStockOperationUsecase);
    }

    @Test
    void propagatesQueueDiscoveryFailure() {
        var failure = new IllegalStateException("scan failed");
        when(backlog.findQueueKeysWithAvailableStock(TODAY, 200, null)).thenThrow(failure);
        assertThatThrownBy(usecase::execute).isSameAs(failure);
        verifyNoMoreInteractions(assignNextStockOperationUsecase);
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
