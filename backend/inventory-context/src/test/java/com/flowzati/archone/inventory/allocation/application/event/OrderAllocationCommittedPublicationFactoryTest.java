package com.flowzati.archone.inventory.allocation.application.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.contracts.promising.v1.AllocationChannels;
import com.flowzati.archone.contracts.promising.v1.OrderAllocationCommittedIntegrationEvent;
import com.flowzati.archone.inventory.allocation.application.result.AllocationCommitResult;
import com.flowzati.archone.inventory.allocation.application.result.AllocationCommitResult.CommittedAllocationMove;
import com.flowzati.archone.inventory.allocation.application.result.AllocationCommitResult.CommittedBatchPick;
import com.flowzati.archone.inventory.allocation.domain.valueobject.SourceAllocationUnit;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("order allocation committed publication")
class OrderAllocationCommittedPublicationFactoryTest {

    @Test
    @DisplayName("同一則 canonical event 同時保留 Ordering 與 fulfillment 所需資料")
    void shouldCreateTheCanonicalOrderAllocationCommitment() {
        UUID orderId = uuid(1);
        UUID orderLineId = uuid(2);
        UUID pickingId = uuid(3);
        UUID moveId = uuid(4);
        Instant requiredBy = Instant.parse("2026-08-20T00:00:00Z");
        Instant occurredAt = Instant.parse("2026-08-18T00:00:00Z");
        AllocationCommitResult result = new AllocationCommitResult(
                uuid(10),
                SourceAllocationUnit.primaryOrder(orderId.toString()),
                uuid(11),
                uuid(12),
                uuid(13),
                requiredBy,
                50,
                occurredAt,
                List.of(new CommittedAllocationMove(
                        uuid(10),
                        uuid(14),
                        orderLineId.toString(),
                        moveId,
                        pickingId,
                        "SKU-A",
                        uuid(13),
                        5,
                        List.of(new CommittedBatchPick(uuid(15), 5)))));

        var publication = OrderAllocationCommittedPublicationFactory.create(result);

        assertThat(publication.target().destination()).isEqualTo(AllocationChannels.ALLOCATION_EVENTS);
        var event = (OrderAllocationCommittedIntegrationEvent) publication.event();
        assertThat(event.getOrderId()).isEqualTo(orderId);
        assertThat(event.getAllocationId()).isEqualTo(pickingId);
        assertThat(event.getLines()).singleElement().satisfies(line -> {
            assertThat(line.orderLineId()).isEqualTo(orderLineId);
            assertThat(line.moveId()).isEqualTo(moveId);
        });
        assertThat(event.getDispatchBy()).isEqualTo(requiredBy);
        assertThat(event.getReleasePriority()).isEqualTo(50);
        assertThat(event.getCommittedAt()).isEqualTo(occurredAt);
    }

    private static UUID uuid(int seed) {
        return UUID.fromString(String.format("00000000-0000-7000-8000-%012d", seed));
    }
}
