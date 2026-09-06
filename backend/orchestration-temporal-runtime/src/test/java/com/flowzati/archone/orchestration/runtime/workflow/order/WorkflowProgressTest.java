package com.flowzati.archone.orchestration.runtime.workflow.order;

import static com.flowzati.archone.orchestration.contract.workflow.order.result.OrderFulfillmentPhase.NOT_STARTED;
import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.orchestration.contract.workflow.order.result.OrderFulfillmentPhase;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class WorkflowProgressTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-08-24T10:00:00Z");

    @Test
    void createsInitialProgressWithoutAnOutcomeOrEntryTime() {
        WorkflowProgress progress = new WorkflowProgress();

        assertThat(progress.phase()).isEqualTo(NOT_STARTED);
        assertThat(progress.outcome()).isNull();
        assertThat(progress.phaseEnteredAt()).isNull();
    }

    @ParameterizedTest
    @EnumSource(
            value = OrderFulfillmentPhase.class,
            names = {"NOT_STARTED", "FINISHED"},
            mode = EnumSource.Mode.EXCLUDE)
    void entersThePhaseWithItsEntryTime(OrderFulfillmentPhase phase) {
        WorkflowProgress progress = new WorkflowProgress();

        progress.enterPhase(phase, OCCURRED_AT);

        assertThat(progress.phase()).isEqualTo(phase);
        assertThat(progress.outcome()).isNull();
        assertThat(progress.phaseEnteredAt()).isEqualTo(OCCURRED_AT);
    }

    @Test
    void updatesTheSameProgressAcrossPhases() {
        WorkflowProgress progress = new WorkflowProgress();
        progress.enterPhase(OrderFulfillmentPhase.ALLOCATION, OCCURRED_AT);

        progress.enterPhase(OrderFulfillmentPhase.WAREHOUSE_RELEASE, OCCURRED_AT.plusSeconds(1));

        assertThat(progress.phase()).isEqualTo(OrderFulfillmentPhase.WAREHOUSE_RELEASE);
        assertThat(progress.outcome()).isNull();
        assertThat(progress.phaseEnteredAt()).isEqualTo(OCCURRED_AT.plusSeconds(1));
    }
}
