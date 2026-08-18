package com.flowzati.archone.stock.application.movement;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.flowzati.archone.promising.time.AppClock;
import com.flowzati.archone.stock.application.command.AllocateWaitingDemandCommand;
import com.flowzati.archone.stock.domain.model.WaitingAllocationScope;
import com.flowzati.archone.testsupport.OrderFixtures;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("一筆 transactional allocation attempt")
class TransactionalAllocationAttemptTest {

  @Test
  @DisplayName("availability 與 scheduler 共用 demand-first、單 demand transaction boundary")
  void shouldDelegateOneBoundedDemandFirstIteration() {
    Instant now = Instant.parse("2026-08-03T01:00:00Z");
    AppClock clock = new AppClock(Clock.fixed(now, ZoneId.of("UTC")), "Asia/Taipei");
    AllocationAttemptCoordinator coordinator = mock(AllocationAttemptCoordinator.class);
    TransactionalAllocationAttempt allocationAttempt =
        new TransactionalAllocationAttempt(coordinator, clock, 3);
    AllocateWaitingDemandCommand command = new AllocateWaitingDemandCommand(
        OrderFixtures.OWNER_ID, OrderFixtures.FACILITY_ID,
        OrderFixtures.LOCATION_ID, "SKU-1");

    allocationAttempt.attempt(command);

    verify(coordinator).allocateOne(
        new WaitingAllocationScope(
            OrderFixtures.OWNER_ID, OrderFixtures.FACILITY_ID,
            OrderFixtures.LOCATION_ID, "SKU-1"),
        "SKU-1", 3, LocalDate.of(2026, 8, 3), now);
  }
}
