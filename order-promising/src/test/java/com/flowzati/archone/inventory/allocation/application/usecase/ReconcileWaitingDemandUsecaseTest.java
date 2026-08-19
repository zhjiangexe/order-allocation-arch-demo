package com.flowzati.archone.inventory.allocation.application.usecase;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.flowzati.archone.bootstrap.time.ConfiguredBusinessClock;
import com.flowzati.archone.inventory.allocation.application.command.AllocateWaitingDemandCommand;
import com.flowzati.archone.inventory.allocation.application.TransactionalAllocationAttempt;
import com.flowzati.archone.inventory.allocation.domain.valueobject.WaitingAllocationScope;
import com.flowzati.archone.inventory.allocation.domain.repository.AllocationDemandRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

@DisplayName("ReconcileWaitingDemandUsecase")
class ReconcileWaitingDemandUsecaseTest {

  private static final int SCOPE_LIMIT = 5;
  private static final LocalDate TODAY = LocalDate.of(2026, 8, 4);
  private static final UUID OWNER_ID = uuid(1);
  private static final UUID FACILITY_ID = uuid(2);
  private static final UUID LOCATION_ID = uuid(3);

  @Test
  @DisplayName("成功的 scope 放回隊尾，並以 round-robin 用完全域預算")
  void shouldRequeueSuccessfulScopesInRoundRobinOrder() {
    AllocationDemandRepository demands = mock(AllocationDemandRepository.class);
    TransactionalAllocationAttempt allocationAttempt = mock(TransactionalAllocationAttempt.class);
    WaitingAllocationScope firstScope = scope("SKU-1");
    WaitingAllocationScope secondScope = scope("SKU-2");
    AllocateWaitingDemandCommand firstCommand = commandFor(firstScope);
    AllocateWaitingDemandCommand secondCommand = commandFor(secondScope);

    when(demands.findAllocatablePendingScopes(TODAY, SCOPE_LIMIT))
        .thenReturn(List.of(firstScope, secondScope));
    when(allocationAttempt.attempt(firstCommand)).thenReturn(true);
    when(allocationAttempt.attempt(secondCommand)).thenReturn(true);

    new ReconcileWaitingDemandUsecase(demands, allocationAttempt, appClock(), SCOPE_LIMIT)
        .execute();

    InOrder allocationOrder = inOrder(allocationAttempt);
    allocationOrder.verify(allocationAttempt).attempt(firstCommand);
    allocationOrder.verify(allocationAttempt).attempt(secondCommand);
    allocationOrder.verify(allocationAttempt).attempt(firstCommand);
    allocationOrder.verify(allocationAttempt).attempt(secondCommand);
    allocationOrder.verify(allocationAttempt).attempt(firstCommand);
    verifyNoMoreInteractions(allocationAttempt);
  }

  private static WaitingAllocationScope scope(String skuCode) {
    return new WaitingAllocationScope(OWNER_ID, FACILITY_ID, LOCATION_ID, skuCode);
  }

  private static AllocateWaitingDemandCommand commandFor(WaitingAllocationScope scope) {
    return new AllocateWaitingDemandCommand(
        scope.ownerId(), scope.facilityId(), scope.locationId(), scope.skuCode());
  }

  private static ConfiguredBusinessClock appClock() {
    return new ConfiguredBusinessClock(
        Clock.fixed(Instant.parse("2026-08-03T16:00:00Z"), ZoneId.of("UTC")),
        "Asia/Taipei");
  }

  private static UUID uuid(int seed) {
    return UUID.fromString(String.format("00000000-0000-7000-8000-%012d", seed));
  }
}
