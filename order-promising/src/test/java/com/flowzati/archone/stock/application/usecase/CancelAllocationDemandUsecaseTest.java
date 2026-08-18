package com.flowzati.archone.stock.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.stock.application.command.CancelAllocationDemandCommand;
import com.flowzati.archone.stock.application.movement.AllocationExecutionCancellationCoordinator;
import com.flowzati.archone.stock.application.movement.ExternalCancellationDecision;
import com.flowzati.archone.stock.domain.model.AllocationCancellationState;
import com.flowzati.archone.stock.domain.model.AllocationDemand;
import com.flowzati.archone.stock.domain.model.AllocationDemandLineRequest;
import com.flowzati.archone.stock.domain.model.AllocationSourceType;
import com.flowzati.archone.stock.domain.model.SourceAllocationUnit;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CancelAllocationDemandUsecaseTest {

  private static final Instant NOW = Instant.parse("2026-08-18T06:00:00Z");
  private AllocationCancellationTransactions transactions;
  private AllocationExecutionCancellationCoordinator coordinator;
  private CancelAllocationDemandUsecase usecase;

  @BeforeEach
  void setUp() {
    transactions = mock(AllocationCancellationTransactions.class);
    coordinator = mock(AllocationExecutionCancellationCoordinator.class);
    usecase = new CancelAllocationDemandUsecase(
        transactions, coordinator, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  @DisplayName("pending demand 不呼叫外部系統，直接保存確認再完成本地取消")
  void shouldCancelPendingWithoutExternalCoordination() {
    AllocationDemand demand = demand();
    CancelAllocationDemandCommand command = command(demand.id());
    when(transactions.begin(demand.id(), command.cancellationOperationId(), NOW))
        .thenReturn(new AllocationCancellationCheckpoint(
            demand, AllocationCancellationState.STARTED));
    when(transactions.completePendingOrRefresh(
        demand.id(), command.cancellationOperationId(), NOW))
        .thenReturn(new AllocationCancellationCheckpoint(
            demand, AllocationCancellationState.COMPLETED));

    assertThat(usecase.execute(command)).isEqualTo(AllocationCancellationResult.COMPLETED);

    verify(coordinator, never()).cancelExecution(
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  @DisplayName("pending cancellation 與 allocation 競爭時若 allocation 先贏必須重新走外部協調")
  void shouldRequireExternalCoordinationWhenAllocationWinsPendingCancellationRace() {
    AllocationDemand pending = demand();
    AllocationDemand allocated = demand();
    allocated.markAllocated();
    CancelAllocationDemandCommand command = command(pending.id());
    when(transactions.begin(pending.id(), command.cancellationOperationId(), NOW))
        .thenReturn(new AllocationCancellationCheckpoint(
            pending, AllocationCancellationState.STARTED));
    when(transactions.completePendingOrRefresh(
        pending.id(), command.cancellationOperationId(), NOW))
        .thenReturn(new AllocationCancellationCheckpoint(
            allocated, AllocationCancellationState.STARTED));
    when(coordinator.cancelExecution(allocated, command.cancellationOperationId()))
        .thenReturn(ExternalCancellationDecision.CONFIRMED);
    when(transactions.recordExternalDecision(
        allocated.id(), command.cancellationOperationId(),
        ExternalCancellationDecision.CONFIRMED, NOW))
        .thenReturn(new AllocationCancellationCheckpoint(
            allocated, AllocationCancellationState.EXTERNAL_CONFIRMED));
    when(transactions.complete(allocated.id(), command.cancellationOperationId(), NOW))
        .thenReturn(AllocationCancellationResult.COMPLETED);

    assertThat(usecase.execute(command)).isEqualTo(AllocationCancellationResult.COMPLETED);

    verify(coordinator).cancelExecution(allocated, command.cancellationOperationId());
  }

  @Test
  @DisplayName("外部拒絕後同 operation retry 保留原決定且不釋放本地 reservation")
  void shouldRetainExternalRejection() {
    AllocationDemand allocated = demand();
    allocated.markAllocated();
    CancelAllocationDemandCommand command = command(allocated.id());
    when(transactions.begin(allocated.id(), command.cancellationOperationId(), NOW))
        .thenReturn(new AllocationCancellationCheckpoint(
            allocated, AllocationCancellationState.EXTERNAL_REJECTED));

    assertThat(usecase.execute(command)).isEqualTo(AllocationCancellationResult.NOT_CANCELLABLE);

    verify(coordinator, never()).cancelExecution(
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    verify(transactions, never()).complete(
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any());
  }

  @Test
  @DisplayName("外部已確認但本地 commit 前 crash 的 retry 不重送外部取消，直接恢復")
  void shouldResumeAfterDurableExternalConfirmation() {
    AllocationDemand allocated = demand();
    allocated.markAllocated();
    CancelAllocationDemandCommand command = command(allocated.id());
    when(transactions.begin(allocated.id(), command.cancellationOperationId(), NOW))
        .thenReturn(new AllocationCancellationCheckpoint(
            allocated, AllocationCancellationState.EXTERNAL_CONFIRMED));
    when(transactions.complete(allocated.id(), command.cancellationOperationId(), NOW))
        .thenReturn(AllocationCancellationResult.COMPLETED);

    assertThat(usecase.execute(command)).isEqualTo(AllocationCancellationResult.COMPLETED);

    verify(coordinator, never()).cancelExecution(
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    verify(transactions).complete(allocated.id(), command.cancellationOperationId(), NOW);
  }

  @Test
  @DisplayName("allocated demand 的外部 coordinator 在 checkpoint transactions 之間只呼叫一次")
  void shouldPersistExternalDecisionBeforeLocalCompletion() {
    AllocationDemand allocated = demand();
    allocated.markAllocated();
    CancelAllocationDemandCommand command = command(allocated.id());
    when(transactions.begin(allocated.id(), command.cancellationOperationId(), NOW))
        .thenReturn(new AllocationCancellationCheckpoint(
            allocated, AllocationCancellationState.STARTED));
    when(coordinator.cancelExecution(allocated, command.cancellationOperationId()))
        .thenReturn(ExternalCancellationDecision.CONFIRMED);
    when(transactions.recordExternalDecision(
        allocated.id(), command.cancellationOperationId(),
        ExternalCancellationDecision.CONFIRMED, NOW))
        .thenReturn(new AllocationCancellationCheckpoint(
            allocated, AllocationCancellationState.EXTERNAL_CONFIRMED));
    when(transactions.complete(allocated.id(), command.cancellationOperationId(), NOW))
        .thenReturn(AllocationCancellationResult.COMPLETED);

    assertThat(usecase.execute(command)).isEqualTo(AllocationCancellationResult.COMPLETED);

    verify(coordinator).cancelExecution(allocated, command.cancellationOperationId());
    verify(transactions).recordExternalDecision(
        allocated.id(), command.cancellationOperationId(),
        ExternalCancellationDecision.CONFIRMED, NOW);
    verify(transactions).complete(allocated.id(), command.cancellationOperationId(), NOW);
  }

  @Test
  @DisplayName("外部拒絕會先持久化固定決定，再回 not cancellable 且不執行 local completion")
  void shouldPersistExternalRejectionWithoutLocalCancellation() {
    AllocationDemand allocated = demand();
    allocated.markAllocated();
    CancelAllocationDemandCommand command = command(allocated.id());
    when(transactions.begin(allocated.id(), command.cancellationOperationId(), NOW))
        .thenReturn(new AllocationCancellationCheckpoint(
            allocated, AllocationCancellationState.STARTED));
    when(coordinator.cancelExecution(allocated, command.cancellationOperationId()))
        .thenReturn(ExternalCancellationDecision.REJECTED);
    when(transactions.recordExternalDecision(
        allocated.id(), command.cancellationOperationId(),
        ExternalCancellationDecision.REJECTED, NOW))
        .thenReturn(new AllocationCancellationCheckpoint(
            allocated, AllocationCancellationState.EXTERNAL_REJECTED));

    assertThat(usecase.execute(command)).isEqualTo(AllocationCancellationResult.NOT_CANCELLABLE);

    verify(transactions, never()).complete(
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any());
  }

  @Test
  @DisplayName("外部確認後、decision 落盤前 crash 會用同 operation id 安全重試 coordinator")
  void shouldRetryExternalCoordinatorWithSameOperationAfterPrePersistenceCrash() {
    AllocationDemand allocated = demand();
    allocated.markAllocated();
    CancelAllocationDemandCommand command = command(allocated.id());
    when(transactions.begin(allocated.id(), command.cancellationOperationId(), NOW))
        .thenReturn(
            new AllocationCancellationCheckpoint(allocated, AllocationCancellationState.STARTED),
            new AllocationCancellationCheckpoint(allocated, AllocationCancellationState.STARTED));
    when(coordinator.cancelExecution(allocated, command.cancellationOperationId()))
        .thenReturn(ExternalCancellationDecision.CONFIRMED);
    when(transactions.recordExternalDecision(
        allocated.id(), command.cancellationOperationId(),
        ExternalCancellationDecision.CONFIRMED, NOW))
        .thenThrow(new IllegalStateException("simulated crash"))
        .thenReturn(new AllocationCancellationCheckpoint(
            allocated, AllocationCancellationState.EXTERNAL_CONFIRMED));
    when(transactions.complete(allocated.id(), command.cancellationOperationId(), NOW))
        .thenReturn(AllocationCancellationResult.COMPLETED);

    assertThatThrownBy(() -> usecase.execute(command))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("simulated crash");
    assertThat(usecase.execute(command)).isEqualTo(AllocationCancellationResult.COMPLETED);

    verify(coordinator, times(2)).cancelExecution(allocated, command.cancellationOperationId());
  }

  private static AllocationDemand demand() {
    return AllocationDemand.accept(
        uuid(1), new SourceAllocationUnit(AllocationSourceType.TRANSFER, "transfer-1", "LEG-A"),
        uuid(2), uuid(3), uuid(4), NOW.plusSeconds(3600), 50, NOW.minusSeconds(60),
        List.of(new AllocationDemandLineRequest("line-1", "SKU-A", 1)), () -> uuid(5));
  }

  private static CancelAllocationDemandCommand command(UUID demandId) {
    return new CancelAllocationDemandCommand(demandId, uuid(6));
  }

  private static UUID uuid(int seed) {
    return UUID.fromString(String.format("00000000-0000-7000-8000-%012d", seed));
  }
}
