package com.flowzati.archone.inventory.allocation.domain.valueobject;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.inventory.allocation.domain.aggregate.AllocationDemand;
import com.flowzati.archone.inventory.allocation.domain.type.AllocationSourceType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("pending demand queue position")
class PendingDemandQueuePositionTest {

    private static final UUID OWNER_ID = uuid(1);
    private static final UUID FACILITY_ID = uuid(2);
    private static final UUID LOCATION_ID = uuid(3);

    @Test
    @DisplayName("A+B demand 不是 B queue head 時不得前進，並能指出 blocker")
    void shouldExposeBlockingQueueHeadsAcrossEveryRequiredSku() {
        AllocationDemand earlierB = queueDemand(
                40, Instant.parse("2026-08-18T00:00:00Z"), List.of(new AllocationDemandLineRequest("b", "SKU-B", 1)));
        AllocationDemand candidateAb = queueDemand(
                50,
                Instant.parse("2026-08-18T00:01:00Z"),
                List.of(
                        new AllocationDemandLineRequest("a", "SKU-A", 1),
                        new AllocationDemandLineRequest("b", "SKU-B", 1)));

        PendingDemandQueuePosition position = new PendingDemandQueuePosition(
                candidateAb, List.of(queueHead(candidateAb, "SKU-A"), queueHead(earlierB, "SKU-B")));

        assertThat(position.isHeadOfEveryRequiredQueue()).isFalse();
        assertThat(position.blockingQueueHeads())
                .extracting(AllocationQueueHead::allocationDemandId)
                .containsExactly(earlierB.id());
    }

    @Test
    @DisplayName("demand 位於每個 required queue head 時可以前進")
    void shouldRecognizeHeadOfEveryRequiredQueue() {
        AllocationDemand candidateC = queueDemand(
                70, Instant.parse("2026-08-18T00:01:00Z"), List.of(new AllocationDemandLineRequest("c", "SKU-C", 1)));

        PendingDemandQueuePosition position =
                new PendingDemandQueuePosition(candidateC, List.of(queueHead(candidateC, "SKU-C")));

        assertThat(position.isHeadOfEveryRequiredQueue()).isTrue();
        assertThat(position.blockingQueueHeads()).isEmpty();
    }

    private static AllocationQueueHead queueHead(AllocationDemand demand, String skuCode) {
        return new AllocationQueueHead(skuCode, demand.id(), demand.source().sourceType(), demand.enqueuedAt());
    }

    private static AllocationDemand queueDemand(int id, Instant enqueuedAt, List<AllocationDemandLineRequest> lines) {
        AtomicInteger lineIds = new AtomicInteger(id + 1);
        return AllocationDemand.accept(
                uuid(id),
                new SourceAllocationUnit(AllocationSourceType.MANUAL, "manual-" + id, "PRIMARY"),
                OWNER_ID,
                FACILITY_ID,
                LOCATION_ID,
                Instant.parse("2026-08-20T00:00:00Z"),
                50,
                enqueuedAt,
                lines,
                () -> uuid(lineIds.getAndIncrement()));
    }

    private static UUID uuid(int seed) {
        return UUID.fromString(String.format("00000000-0000-7000-8000-%012d", seed));
    }
}
