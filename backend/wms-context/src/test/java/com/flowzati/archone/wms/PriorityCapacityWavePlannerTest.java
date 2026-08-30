package com.flowzati.archone.wms;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.wms.outbound.domain.service.impl.PriorityCapacityWavePlanner;
import com.flowzati.archone.wms.outbound.domain.valueobject.WaveCandidate;
import com.flowzati.archone.wms.outbound.domain.valueobject.WavePlanningPolicy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PriorityCapacityWavePlannerTest {

    private static final UUID FACILITY_ID = new UUID(0, 10);
    private static final Instant T0 = Instant.parse("2026-08-06T01:00:00Z");
    private final PriorityCapacityWavePlanner planner = new PriorityCapacityWavePlanner();

    @Test
    void filtersHardConstraintsThenGreedilyFillsRemainingCapacityInStablePriorityOrder() {
        WaveCandidate largeHighPriority = candidate(1, FACILITY_ID, 90, 2, 4, 100);
        WaveCandidate tooLargeForRemainder = candidate(2, FACILITY_ID, 80, 1, 4, 100);
        WaveCandidate smallLowerPriority = candidate(3, FACILITY_ID, 70, 1, 1, 100);
        WaveCandidate outsideCutoff = candidate(4, FACILITY_ID, 100, 1, 1, 10_000);
        WaveCandidate anotherFacility = candidate(5, new UUID(0, 99), 100, 1, 1, 100);
        WavePlanningPolicy policy = new WavePlanningPolicy(FACILITY_ID, T0.plusSeconds(1_000), 2, 3, 5);

        var forward = planner.plan(
                List.of(smallLowerPriority, outsideCutoff, tooLargeForRemainder, anotherFacility, largeHighPriority),
                policy);
        var reverse = planner.plan(
                List.of(largeHighPriority, anotherFacility, tooLargeForRemainder, outsideCutoff, smallLowerPriority),
                policy);

        assertThat(forward)
                .extracting(assignment -> assignment.shipmentId())
                .containsExactly(largeHighPriority.shipmentId(), smallLowerPriority.shipmentId());
        assertThat(reverse).isEqualTo(forward);
        assertThat(forward.stream()
                        .mapToInt(assignment -> assignment.unitCount())
                        .sum())
                .isEqualTo(5);
        assertThat(forward.stream()
                        .mapToInt(assignment -> assignment.lineCount())
                        .sum())
                .isEqualTo(3);
    }

    private WaveCandidate candidate(
            long id, UUID facilityId, int priority, int lineCount, int unitCount, long dispatchAfterSeconds) {
        return new WaveCandidate(
                new UUID(0, id),
                facilityId,
                T0.plusSeconds(dispatchAfterSeconds),
                priority,
                T0.plusSeconds(id),
                lineCount,
                unitCount);
    }
}
