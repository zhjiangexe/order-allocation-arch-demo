package com.flowzati.archone.inventory.allocation.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.inventory.allocation.application.AllocationReservationCanceller;
import com.flowzati.archone.inventory.allocation.domain.aggregate.AllocationCancellationOperation;
import com.flowzati.archone.inventory.allocation.domain.type.AllocationCancellationState;
import com.flowzati.archone.inventory.allocation.domain.aggregate.AllocationDemand;
import com.flowzati.archone.inventory.allocation.domain.valueobject.AllocationDemandLineRequest;
import com.flowzati.archone.inventory.allocation.domain.type.AllocationDemandStatus;
import com.flowzati.archone.inventory.allocation.domain.type.AllocationSourceType;
import com.flowzati.archone.inventory.allocation.domain.valueobject.SourceAllocationUnit;
import com.flowzati.archone.inventory.allocation.domain.repository.AllocationCancellationOperationRepository;
import com.flowzati.archone.inventory.allocation.domain.repository.AllocationDemandRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("allocation cancellation local transactions")
class AllocationCancellationTransactionsTest {

  private static final Instant NOW = Instant.parse("2026-08-18T07:00:00Z");
  private AllocationDemandRepository demandRepository;
  private AllocationCancellationOperationRepository operationRepository;
  private AllocationReservationCanceller allocationReservationCanceller;
  private AllocationCancellationTransactions transactions;

  @BeforeEach
  void setUp() {
    demandRepository = mock(AllocationDemandRepository.class);
    operationRepository = mock(AllocationCancellationOperationRepository.class);
    allocationReservationCanceller = mock(AllocationReservationCanceller.class);
    transactions = new AllocationCancellationTransactions(
        demandRepository, operationRepository, allocationReservationCanceller);
  }

  @Test
  @DisplayName("pending demand 與 operation 在同一 local transaction 完成且只釋放一次")
  void shouldCompletePendingCancellationAtomically() {
    AllocationDemand demand = demand();
    AllocationCancellationOperation operation = operation(demand.id());
    given(demand, operation);

    AllocationCancellationCheckpoint result = transactions.completePendingOrRefresh(
        demand.id(), operation.operationId(), NOW);

    assertThat(result.state()).isEqualTo(AllocationCancellationState.COMPLETED);
    assertThat(demand.status()).isEqualTo(AllocationDemandStatus.CANCELLED);
    verify(allocationReservationCanceller).cancelForDemand(demand.id());
    verify(demandRepository).save(demand);
    verify(operationRepository).save(operation);
  }

  @Test
  @DisplayName("重讀時 demand 已 allocated 就保留 STARTED，交回外部協調且不碰 reservation")
  void shouldRefreshAllocatedRaceWinnerWithoutLocalCancellation() {
    AllocationDemand demand = demand();
    demand.markAllocated();
    AllocationCancellationOperation operation = operation(demand.id());
    given(demand, operation);

    AllocationCancellationCheckpoint result = transactions.completePendingOrRefresh(
        demand.id(), operation.operationId(), NOW);

    assertThat(result.state()).isEqualTo(AllocationCancellationState.STARTED);
    assertThat(result.demand().status()).isEqualTo(AllocationDemandStatus.ALLOCATED);
    verify(allocationReservationCanceller, never()).cancelForDemand(demand.id());
    verify(demandRepository, never()).save(demand);
    verify(operationRepository, never()).save(operation);
  }

  private void given(
      AllocationDemand demand, AllocationCancellationOperation operation) {
    when(demandRepository.findById(demand.id())).thenReturn(Optional.of(demand));
    when(operationRepository.find(demand.id(), operation.operationId()))
        .thenReturn(Optional.of(operation));
  }

  private static AllocationCancellationOperation operation(UUID demandId) {
    return AllocationCancellationOperation.start(demandId, uuid(6), NOW.minusSeconds(1));
  }

  private static AllocationDemand demand() {
    return AllocationDemand.accept(
        uuid(1), new SourceAllocationUnit(AllocationSourceType.MANUAL, "manual-1", "PRIMARY"),
        uuid(2), uuid(3), uuid(4), NOW.plusSeconds(3600), 50, NOW.minusSeconds(60),
        List.of(new AllocationDemandLineRequest("line-1", "SKU-A", 1)), () -> uuid(5));
  }

  private static UUID uuid(int seed) {
    return UUID.fromString(String.format("00000000-0000-7000-8000-%012d", seed));
  }
}
