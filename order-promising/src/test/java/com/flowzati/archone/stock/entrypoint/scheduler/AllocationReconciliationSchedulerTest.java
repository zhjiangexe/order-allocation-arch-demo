package com.flowzati.archone.stock.entrypoint.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowzati.archone.promising.time.AppClock;
import com.flowzati.archone.stock.application.command.AllocateWaitingDemandCommand;
import com.flowzati.archone.stock.application.movement.TransactionalAllocationAttempt;
import com.flowzati.archone.stock.application.usecase.ReconcileWaitingDemandUsecase;
import com.flowzati.archone.stock.domain.model.WaitingAllocationScope;
import com.flowzati.archone.stock.domain.repository.AllocationDemandRepository;
import com.flowzati.archone.testsupport.OrderFixtures;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.OptimisticLockingFailureException;

class AllocationReconciliationSchedulerTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 8, 4);

  @Test
  @DisplayName("關閉 reconciliation 時即使 application 已啟用 scheduling 也不建立 scheduler bean")
  void shouldConditionTheSchedulerBeanInsteadOfTheSchedulingEngine() {
    ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(AllocationReconciliationScheduler.class)
        .withBean(ReconcileWaitingDemandUsecase.class, () -> mock(ReconcileWaitingDemandUsecase.class));

    runner
        .withPropertyValues("archone.allocation.reconciliation-scheduler-enabled=false")
        .run(context -> assertThat(context)
            .doesNotHaveBean(AllocationReconciliationScheduler.class));
    runner
        .withPropertyValues("archone.allocation.reconciliation-scheduler-enabled=true")
        .run(context -> assertThat(context)
            .hasSingleBean(AllocationReconciliationScheduler.class));
  }

  @Test
  @DisplayName("依等待 scope 呼叫同一個 transactional wake use case")
  void shouldReconcileWaitingScopesThroughTheSharedUsecase() {
    AllocationDemandRepository demands = mock(AllocationDemandRepository.class);
    TransactionalAllocationAttempt allocationAttempt = mock(TransactionalAllocationAttempt.class);
    WaitingAllocationScope scope = new WaitingAllocationScope(
        OrderFixtures.OWNER_ID, OrderFixtures.FACILITY_ID, OrderFixtures.LOCATION_ID, "SKU-1");
    when(demands.findAllocatablePendingScopes(TODAY, 25)).thenReturn(List.of(scope));

    new AllocationReconciliationScheduler(reconcileUsecase(demands, allocationAttempt))
        .reconcileAllocatableWaitingDemand();

    verify(allocationAttempt).attempt(new AllocateWaitingDemandCommand(
        OrderFixtures.OWNER_ID,
        OrderFixtures.FACILITY_ID,
        OrderFixtures.LOCATION_ID,
        "SKU-1"));
  }

  @Test
  @DisplayName("沒有等待 scope 時不呼叫配貨")
  void shouldDoNothingWithoutWaitingScopes() {
    AllocationDemandRepository demands = mock(AllocationDemandRepository.class);
    TransactionalAllocationAttempt allocationAttempt = mock(TransactionalAllocationAttempt.class);
    when(demands.findAllocatablePendingScopes(TODAY, 25)).thenReturn(List.of());

    new AllocationReconciliationScheduler(reconcileUsecase(demands, allocationAttempt))
        .reconcileAllocatableWaitingDemand();

    verifyNoInteractions(allocationAttempt);
  }

  @Test
  @DisplayName("樂觀鎖衝突不在同一輪重試，並繼續處理其他 scope")
  void shouldDeferConflictedScopeUntilTheNextRun() {
    AllocationDemandRepository demands = mock(AllocationDemandRepository.class);
    TransactionalAllocationAttempt allocationAttempt = mock(TransactionalAllocationAttempt.class);
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
    when(demands.findAllocatablePendingScopes(TODAY, 25))
        .thenReturn(List.of(conflicted, following));
    doThrow(new OptimisticLockingFailureException("conflict"))
        .when(allocationAttempt).attempt(conflictedCommand);

    new AllocationReconciliationScheduler(reconcileUsecase(demands, allocationAttempt))
        .reconcileAllocatableWaitingDemand();

    verify(allocationAttempt, times(1)).attempt(conflictedCommand);
    verify(allocationAttempt).attempt(followingCommand);
  }

  private AppClock appClock() {
    return new AppClock(
        Clock.fixed(Instant.parse("2026-08-03T16:00:00Z"), ZoneId.of("UTC")),
        "Asia/Taipei");
  }

  private ReconcileWaitingDemandUsecase reconcileUsecase(
      AllocationDemandRepository demands,
      TransactionalAllocationAttempt allocationAttempt
  ) {
    return new ReconcileWaitingDemandUsecase(demands, allocationAttempt, appClock(), 25);
  }
}
