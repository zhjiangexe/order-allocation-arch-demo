package com.flowzati.archone.inventory.allocation.application.source.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.inventory.allocation.domain.event.AllocationCommitted;
import com.flowzati.archone.inventory.allocation.domain.event.AllocationCommitted.CommittedAllocationMove;
import com.flowzati.archone.inventory.allocation.domain.event.AllocationCommitted.CommittedBatchPick;
import com.flowzati.archone.inventory.allocation.domain.event.OrderAllocationCompleted;
import com.flowzati.archone.inventory.allocation.domain.valueobject.SourceAllocationUnit;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("order allocation completion adapter")
class OrderAllocationCompletionAdapterTest {

    @Test
    @DisplayName("保留 v1 orderId、orderLineId 與 picking allocationId mapping")
    void shouldPreserveOrderV1ContractMapping() {
        UUID orderId = uuid(1);
        UUID orderLineId = uuid(2);
        UUID pickingId = uuid(3);
        UUID moveId = uuid(4);
        Instant requiredBy = Instant.parse("2026-08-20T00:00:00Z");
        Instant occurredAt = Instant.parse("2026-08-18T00:00:00Z");
        AllocationCommitted generic = new AllocationCommitted(
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

        OrderAllocationCompleted order = new OrderAllocationCompletionAdapter().translate(generic);

        assertThat(order.orderId()).isEqualTo(orderId);
        assertThat(order.allocationId()).isEqualTo(pickingId);
        assertThat(order.lines().getFirst().orderLineId()).isEqualTo(orderLineId);
        assertThat(order.lines().getFirst().moveId()).isEqualTo(moveId);
        assertThat(order.dispatchBy()).isEqualTo(requiredBy);
        assertThat(order.releasePriority()).isEqualTo(50);
        assertThat(order.allocatedAt()).isEqualTo(occurredAt);
    }

    private static UUID uuid(int seed) {
        return UUID.fromString(String.format("00000000-0000-7000-8000-%012d", seed));
    }
}
