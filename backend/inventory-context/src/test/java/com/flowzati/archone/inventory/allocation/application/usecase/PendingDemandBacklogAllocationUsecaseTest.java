package com.flowzati.archone.inventory.allocation.application.usecase;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.flowzati.archone.foundation.time.BusinessClock;
import com.flowzati.archone.inventory.allocation.application.command.AllocatePendingDemandCommand;
import com.flowzati.archone.inventory.allocation.application.query.PendingDemandBacklogQuery;
import com.flowzati.archone.inventory.allocation.domain.valueobject.AllocationDemandQueueKey;
import com.flowzati.archone.inventory.testsupport.InventoryFixtures;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

@DisplayName("PendingDemandBacklogAllocationUsecase")
class PendingDemandBacklogAllocationUsecaseTest {

    private static final int MAX_ATTEMPTS_PER_RUN = 5;
    private static final long MAX_RUN_DURATION_MS = 45_000;
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 4);
    private static final UUID OWNER_ID = uuid(1);
    private static final UUID FACILITY_ID = uuid(2);
    private static final UUID LOCATION_ID = uuid(3);

    @Test
    @DisplayName("成功的 queue key 進入下一個公平輪次，並用完全域預算")
    void shouldAdvanceSuccessfulQueueKeysInRoundRobinOrder() {
        PendingDemandBacklogQuery backlogQuery = mock(PendingDemandBacklogQuery.class);
        PendingDemandAllocationUsecase pendingDemandAllocationUsecase = mock(PendingDemandAllocationUsecase.class);
        AllocationDemandQueueKey firstQueueKey = queueKey("SKU-1");
        AllocationDemandQueueKey secondQueueKey = queueKey("SKU-2");

        when(backlogQuery.findQueueKeysWithAvailableStock(TODAY, MAX_ATTEMPTS_PER_RUN))
                .thenReturn(List.of(firstQueueKey, secondQueueKey));
        when(pendingDemandAllocationUsecase.execute(commandFor(firstQueueKey))).thenReturn(true);
        when(pendingDemandAllocationUsecase.execute(commandFor(secondQueueKey))).thenReturn(true);

        new PendingDemandBacklogAllocationUsecase(
                        backlogQuery,
                        pendingDemandAllocationUsecase,
                        appClock(),
                        MAX_ATTEMPTS_PER_RUN,
                        MAX_RUN_DURATION_MS)
                .execute();

        InOrder allocationOrder = inOrder(pendingDemandAllocationUsecase);
        allocationOrder.verify(pendingDemandAllocationUsecase).execute(commandFor(firstQueueKey));
        allocationOrder.verify(pendingDemandAllocationUsecase).execute(commandFor(secondQueueKey));
        allocationOrder.verify(pendingDemandAllocationUsecase).execute(commandFor(firstQueueKey));
        allocationOrder.verify(pendingDemandAllocationUsecase).execute(commandFor(secondQueueKey));
        allocationOrder.verify(pendingDemandAllocationUsecase).execute(commandFor(firstQueueKey));
        verifyNoMoreInteractions(pendingDemandAllocationUsecase);
    }

    @Test
    @DisplayName("沒有可配 demand 的 queue 不在同一輪重試，並繼續處理其他 queue")
    void shouldDeferUnallocatedQueueUntilTheNextRunAndContinueWithFollowingQueues() {
        PendingDemandBacklogQuery backlogQuery = mock(PendingDemandBacklogQuery.class);
        PendingDemandAllocationUsecase pendingDemandAllocationUsecase = mock(PendingDemandAllocationUsecase.class);
        AllocationDemandQueueKey unallocatedQueueKey = queueKey("SKU-1");
        AllocationDemandQueueKey followingQueueKey = queueKey("SKU-2");

        when(backlogQuery.findQueueKeysWithAvailableStock(TODAY, MAX_ATTEMPTS_PER_RUN))
                .thenReturn(List.of(unallocatedQueueKey, followingQueueKey));
        when(pendingDemandAllocationUsecase.execute(commandFor(unallocatedQueueKey)))
                .thenReturn(false);
        when(pendingDemandAllocationUsecase.execute(commandFor(followingQueueKey)))
                .thenReturn(false);

        new PendingDemandBacklogAllocationUsecase(
                        backlogQuery,
                        pendingDemandAllocationUsecase,
                        appClock(),
                        MAX_ATTEMPTS_PER_RUN,
                        MAX_RUN_DURATION_MS)
                .execute();

        verify(pendingDemandAllocationUsecase, times(1)).execute(commandFor(unallocatedQueueKey));
        verify(pendingDemandAllocationUsecase, times(1)).execute(commandFor(followingQueueKey));
        verifyNoMoreInteractions(pendingDemandAllocationUsecase);
    }

    @Test
    @DisplayName("單一 queue 非預期失敗時繼續處理後續 queue")
    void shouldIsolateUnexpectedQueueFailureAndContinueWithFollowingQueues() {
        PendingDemandBacklogQuery backlogQuery = mock(PendingDemandBacklogQuery.class);
        PendingDemandAllocationUsecase pendingDemandAllocationUsecase = mock(PendingDemandAllocationUsecase.class);
        AllocationDemandQueueKey failedQueueKey = queueKey("SKU-1");
        AllocationDemandQueueKey followingQueueKey = queueKey("SKU-2");

        when(backlogQuery.findQueueKeysWithAvailableStock(TODAY, MAX_ATTEMPTS_PER_RUN))
                .thenReturn(List.of(failedQueueKey, followingQueueKey));
        when(pendingDemandAllocationUsecase.execute(commandFor(failedQueueKey)))
                .thenThrow(new IllegalStateException("unexpected"));
        when(pendingDemandAllocationUsecase.execute(commandFor(followingQueueKey)))
                .thenReturn(false);

        new PendingDemandBacklogAllocationUsecase(
                        backlogQuery,
                        pendingDemandAllocationUsecase,
                        appClock(),
                        MAX_ATTEMPTS_PER_RUN,
                        MAX_RUN_DURATION_MS)
                .execute();

        InOrder allocationOrder = inOrder(pendingDemandAllocationUsecase);
        allocationOrder.verify(pendingDemandAllocationUsecase).execute(commandFor(failedQueueKey));
        allocationOrder.verify(pendingDemandAllocationUsecase).execute(commandFor(followingQueueKey));
        verifyNoMoreInteractions(pendingDemandAllocationUsecase);
    }

    @Test
    @DisplayName("超過單輪時間預算後停止新的 queue 嘗試")
    void shouldStopStartingAllocationAttemptsAfterTheRunDeadline() {
        PendingDemandBacklogQuery backlogQuery = mock(PendingDemandBacklogQuery.class);
        PendingDemandAllocationUsecase pendingDemandAllocationUsecase = mock(PendingDemandAllocationUsecase.class);
        BusinessClock clock = mock(BusinessClock.class);
        AllocationDemandQueueKey firstQueueKey = queueKey("SKU-1");
        AllocationDemandQueueKey secondQueueKey = queueKey("SKU-2");
        Instant startedAt = Instant.parse("2026-08-03T01:00:00Z");

        when(clock.today()).thenReturn(TODAY);
        when(clock.instant()).thenReturn(startedAt, startedAt.plusMillis(1), startedAt.plusMillis(MAX_RUN_DURATION_MS));
        when(backlogQuery.findQueueKeysWithAvailableStock(TODAY, MAX_ATTEMPTS_PER_RUN))
                .thenReturn(List.of(firstQueueKey, secondQueueKey));
        when(pendingDemandAllocationUsecase.execute(commandFor(firstQueueKey))).thenReturn(true);

        new PendingDemandBacklogAllocationUsecase(
                        backlogQuery, pendingDemandAllocationUsecase, clock, MAX_ATTEMPTS_PER_RUN, MAX_RUN_DURATION_MS)
                .execute();

        verify(pendingDemandAllocationUsecase).execute(commandFor(firstQueueKey));
        verifyNoMoreInteractions(pendingDemandAllocationUsecase);
    }

    @Test
    @DisplayName("等待 queue key 掃描失敗時中斷整輪 reconciliation")
    void shouldPropagateQueueKeyScanFailure() {
        PendingDemandBacklogQuery backlogQuery = mock(PendingDemandBacklogQuery.class);
        PendingDemandAllocationUsecase pendingDemandAllocationUsecase = mock(PendingDemandAllocationUsecase.class);
        IllegalStateException failure = new IllegalStateException("scan failed");
        when(backlogQuery.findQueueKeysWithAvailableStock(TODAY, MAX_ATTEMPTS_PER_RUN))
                .thenThrow(failure);

        PendingDemandBacklogAllocationUsecase usecase = new PendingDemandBacklogAllocationUsecase(
                backlogQuery, pendingDemandAllocationUsecase, appClock(), MAX_ATTEMPTS_PER_RUN, MAX_RUN_DURATION_MS);

        assertThatThrownBy(usecase::execute).isSameAs(failure);
        verifyNoMoreInteractions(pendingDemandAllocationUsecase);
    }

    private static AllocationDemandQueueKey queueKey(String skuCode) {
        return new AllocationDemandQueueKey(OWNER_ID, FACILITY_ID, LOCATION_ID, skuCode);
    }

    private static AllocatePendingDemandCommand commandFor(AllocationDemandQueueKey queueKey) {
        return new AllocatePendingDemandCommand(
                queueKey.ownerId(), queueKey.facilityId(), queueKey.locationId(), queueKey.skuCode());
    }

    private static BusinessClock appClock() {
        return InventoryFixtures.businessClock(
                Clock.fixed(Instant.parse("2026-08-03T16:00:00Z"), ZoneId.of("UTC")), "Asia/Taipei");
    }

    private static UUID uuid(int seed) {
        return UUID.fromString(String.format("00000000-0000-7000-8000-%012d", seed));
    }
}
