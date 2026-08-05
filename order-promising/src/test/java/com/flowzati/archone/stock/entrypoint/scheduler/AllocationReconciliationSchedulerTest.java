package com.flowzati.archone.stock.entrypoint.scheduler;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowzati.archone.common.time.AppClock;
import com.flowzati.archone.stock.application.command.AllocateWaitingDemandCommand;
import com.flowzati.archone.stock.application.usecase.AllocateWaitingDemandUsecase;
import com.flowzati.archone.stock.domain.model.WaitingAllocationScope;
import com.flowzati.archone.stock.domain.repository.StockMoveRepository;
import com.flowzati.archone.testsupport.OrderFixtures;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

class AllocationReconciliationSchedulerTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 8, 4);

  @Test
  @DisplayName("依等待 scope 呼叫同一個 transactional wake use case")
  void shouldReconcileWaitingScopesThroughTheSharedUsecase() {
    StockMoveRepository moves = mock(StockMoveRepository.class);
    AllocateWaitingDemandUsecase usecase = mock(AllocateWaitingDemandUsecase.class);
    WaitingAllocationScope scope = new WaitingAllocationScope(
        OrderFixtures.OWNER_ID, OrderFixtures.FACILITY_ID, OrderFixtures.LOCATION_ID, "SKU-1");
    when(moves.findAllocatableWaitingScopes(TODAY, 25)).thenReturn(List.of(scope));

    new AllocationReconciliationScheduler(moves, usecase, appClock(), 25)
        .reconcileAllocatableWaitingDemand();

    verify(usecase).handle(new AllocateWaitingDemandCommand(
        OrderFixtures.OWNER_ID,
        OrderFixtures.FACILITY_ID,
        OrderFixtures.LOCATION_ID,
        "SKU-1"));
  }

  @Test
  @DisplayName("沒有等待 scope 時不呼叫配貨")
  void shouldDoNothingWithoutWaitingScopes() {
    StockMoveRepository moves = mock(StockMoveRepository.class);
    AllocateWaitingDemandUsecase usecase = mock(AllocateWaitingDemandUsecase.class);
    when(moves.findAllocatableWaitingScopes(TODAY, 25)).thenReturn(List.of());

    new AllocationReconciliationScheduler(moves, usecase, appClock(), 25)
        .reconcileAllocatableWaitingDemand();

    verifyNoInteractions(usecase);
  }

  @Test
  @DisplayName("樂觀鎖衝突不在同一輪重試，並繼續處理其他 scope")
  void shouldDeferConflictedScopeUntilTheNextRun() {
    StockMoveRepository moves = mock(StockMoveRepository.class);
    AllocateWaitingDemandUsecase usecase = mock(AllocateWaitingDemandUsecase.class);
    WaitingAllocationScope conflicted = new WaitingAllocationScope(
        OrderFixtures.OWNER_ID, OrderFixtures.FACILITY_ID, OrderFixtures.LOCATION_ID, "SKU-1");
    WaitingAllocationScope following = new WaitingAllocationScope(
        OrderFixtures.OWNER_ID, OrderFixtures.FACILITY_ID, OrderFixtures.LOCATION_ID, "SKU-2");
    AllocateWaitingDemandCommand conflictedCommand = new AllocateWaitingDemandCommand(
        OrderFixtures.OWNER_ID,
        OrderFixtures.FACILITY_ID,
        OrderFixtures.LOCATION_ID,
        "SKU-1");
    AllocateWaitingDemandCommand followingCommand = new AllocateWaitingDemandCommand(
        OrderFixtures.OWNER_ID,
        OrderFixtures.FACILITY_ID,
        OrderFixtures.LOCATION_ID,
        "SKU-2");
    when(moves.findAllocatableWaitingScopes(TODAY, 25))
        .thenReturn(List.of(conflicted, following));
    doThrow(new OptimisticLockingFailureException("conflict"))
        .when(usecase).handle(conflictedCommand);

    new AllocationReconciliationScheduler(moves, usecase, appClock(), 25)
        .reconcileAllocatableWaitingDemand();

    verify(usecase, times(1)).handle(conflictedCommand);
    verify(usecase).handle(followingCommand);
  }

  private AppClock appClock() {
    return new AppClock(
        Clock.fixed(Instant.parse("2026-08-03T16:00:00Z"), ZoneId.of("UTC")),
        "Asia/Taipei");
  }
}
