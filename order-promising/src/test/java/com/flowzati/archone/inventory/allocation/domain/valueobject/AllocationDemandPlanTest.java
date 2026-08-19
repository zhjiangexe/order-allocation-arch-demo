package com.flowzati.archone.inventory.allocation.domain.valueobject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.inventory.allocation.domain.aggregate.AllocationDemand;
import com.flowzati.archone.inventory.allocation.domain.entity.AllocationDemandLine;
import com.flowzati.archone.inventory.allocation.domain.type.AllocationSourceType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AllocationDemandPlan")
class AllocationDemandPlanTest {

    @Test
    @DisplayName("建立時即保證每條 demand line 都被 picks 完整滿足")
    void shouldCreateOnlyWhenPicksExactlySatisfyDemand() {
        AllocationDemand demand = demand();
        AllocationDemandLine line = demand.lines().getFirst();

        AllocationDemandPlan plan = AllocationDemandPlan.readyToCommit(
                demand,
                List.of(
                        new AllocationBatchPick(demand.id(), line.id(), uuid(20), 2),
                        new AllocationBatchPick(demand.id(), line.id(), uuid(21), 3)));

        assertThat(plan.isReadyToCommit()).isTrue();
        assertThat(plan.picks()).hasSize(2);
    }

    @Test
    @DisplayName("不完整或混入其他 demand 的 picks 無法建立 ready plan")
    void shouldRejectIncompleteOrForeignPicks() {
        AllocationDemand demand = demand();
        AllocationDemandLine line = demand.lines().getFirst();

        assertThatThrownBy(() -> AllocationDemandPlan.readyToCommit(
                        demand, List.of(new AllocationBatchPick(demand.id(), line.id(), uuid(20), 4))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not completely satisfy");

        assertThatThrownBy(() -> AllocationDemandPlan.readyToCommit(
                        demand, List.of(new AllocationBatchPick(uuid(99), line.id(), uuid(20), 5))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("foreign allocation demand line");
    }

    private static AllocationDemand demand() {
        return AllocationDemand.accept(
                uuid(10),
                new SourceAllocationUnit(AllocationSourceType.MANUAL, "manual-1", "PRIMARY"),
                uuid(1),
                uuid(2),
                uuid(3),
                Instant.parse("2026-08-20T00:00:00Z"),
                50,
                Instant.parse("2026-08-18T00:00:00Z"),
                List.of(new AllocationDemandLineRequest("line-1", "SKU-A", 5)),
                () -> uuid(11));
    }

    private static UUID uuid(int seed) {
        return UUID.fromString(String.format("00000000-0000-7000-8000-%012d", seed));
    }
}
