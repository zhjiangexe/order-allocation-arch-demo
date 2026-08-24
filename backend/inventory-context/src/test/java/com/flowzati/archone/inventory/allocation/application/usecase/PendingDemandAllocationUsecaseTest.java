package com.flowzati.archone.inventory.allocation.application.usecase;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.flowzati.archone.foundation.time.BusinessClock;
import com.flowzati.archone.inventory.allocation.application.command.AllocatePendingDemandCommand;
import com.flowzati.archone.inventory.allocation.application.service.reservation.PendingDemandAllocator;
import com.flowzati.archone.inventory.allocation.domain.valueobject.AllocationDemandQueueKey;
import com.flowzati.archone.inventory.testsupport.InventoryFixtures;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PendingDemandAllocationUsecase")
class PendingDemandAllocationUsecaseTest {

    @Test
    @DisplayName("availability 與 scheduler 共用 demand-first、單 demand transaction boundary")
    void shouldDelegateOneBoundedDemandFirstIteration() {
        Instant now = Instant.parse("2026-08-03T01:00:00Z");
        BusinessClock clock = InventoryFixtures.businessClock(Clock.fixed(now, ZoneId.of("UTC")), "Asia/Taipei");
        PendingDemandAllocator pendingDemandAllocator = mock(PendingDemandAllocator.class);
        PendingDemandAllocationUsecase usecase = new PendingDemandAllocationUsecase(pendingDemandAllocator, clock);
        AllocatePendingDemandCommand command = new AllocatePendingDemandCommand(
                InventoryFixtures.OWNER_ID, InventoryFixtures.FACILITY_ID, InventoryFixtures.LOCATION_ID, "SKU-1");

        usecase.execute(command);

        verify(pendingDemandAllocator)
                .tryAllocateQueueHead(
                        new AllocationDemandQueueKey(
                                InventoryFixtures.OWNER_ID,
                                InventoryFixtures.FACILITY_ID,
                                InventoryFixtures.LOCATION_ID,
                                "SKU-1"),
                        LocalDate.of(2026, 8, 3),
                        now);
    }
}
