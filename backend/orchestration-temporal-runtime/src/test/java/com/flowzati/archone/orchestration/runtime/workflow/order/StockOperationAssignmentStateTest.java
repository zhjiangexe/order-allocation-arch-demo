package com.flowzati.archone.orchestration.runtime.workflow.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.orchestration.contract.workflow.order.invocation.AssignedStockMove;
import com.flowzati.archone.orchestration.contract.workflow.order.invocation.StockOperationAssignedInput;
import com.flowzati.archone.orchestration.contract.workflow.order.result.OrderFulfillmentAllocationState;
import io.temporal.failure.ApplicationFailure;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StockOperationAssignmentStateTest {

    private static final StockOperationAssignedInput ASSIGNMENT = new StockOperationAssignedInput(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            List.of(new AssignedStockMove(UUID.randomUUID(), UUID.randomUUID(), "SKU-1", UUID.randomUUID(), 1)),
            Instant.parse("2026-08-24T12:00:00Z"),
            50,
            Instant.parse("2026-08-24T10:00:00Z"));

    @Test
    void receivesAssignmentMessagesOnlyWhileWaitingOrCommitted() {
        StockOperationAssignmentState state = new StockOperationAssignmentState();

        assertThat(state.canReceiveAssignment()).isFalse();

        state.markRequested();

        assertThat(state.canReceiveAssignment()).isTrue();

        state.recordAssigned(ASSIGNMENT);

        assertThat(state.canReceiveAssignment()).isTrue();
        assertThat(state.assignmentSnapshot()).isSameAs(ASSIGNMENT);
    }

    @Test
    void updatesTheSameStateFromNotRequestedToCommitted() {
        StockOperationAssignmentState state = new StockOperationAssignmentState();

        assertThat(state.state()).isEqualTo(OrderFulfillmentAllocationState.NOT_REQUESTED);
        assertThat(state.assignmentSnapshot()).isNull();
        assertThat(state.stockOperationId()).isNull();

        state.markRequested();

        assertThat(state.isRequested()).isTrue();
        assertThat(state.assignmentSnapshot()).isNull();

        state.recordAssigned(ASSIGNMENT);

        assertThat(state.isCommitted()).isTrue();
        assertThat(state.isRequested()).isFalse();
        assertThat(state.assignmentSnapshot()).isSameAs(ASSIGNMENT);
        assertThat(state.stockOperationId()).isEqualTo(ASSIGNMENT.stockOperationId());
    }

    @Test
    void acceptsIdenticalAssignmentWithoutReplacingTheAcceptedFact() {
        StockOperationAssignmentState state = new StockOperationAssignmentState();
        state.markRequested();
        state.recordAssigned(ASSIGNMENT);
        StockOperationAssignedInput replay = assignmentWithId(ASSIGNMENT.stockOperationId());

        state.recordAssigned(replay);

        assertThat(state.isCommitted()).isTrue();
        assertThat(state.assignmentSnapshot()).isSameAs(ASSIGNMENT);
    }

    @Test
    void rejectsConflictingAssignmentWithoutChangingState() {
        StockOperationAssignmentState state = new StockOperationAssignmentState();
        state.markRequested();
        state.recordAssigned(ASSIGNMENT);

        assertThatThrownBy(() -> state.recordAssigned(assignmentWithId(UUID.randomUUID())))
                .isInstanceOf(ApplicationFailure.class)
                .hasMessageContaining("conflicting stock operation assignment facts");

        assertThat(state.isCommitted()).isTrue();
        assertThat(state.assignmentSnapshot()).isSameAs(ASSIGNMENT);
        assertThat(state.stockOperationId()).isEqualTo(ASSIGNMENT.stockOperationId());
    }

    private StockOperationAssignedInput assignmentWithId(UUID stockOperationId) {
        return new StockOperationAssignedInput(
                stockOperationId,
                ASSIGNMENT.orderId(),
                ASSIGNMENT.ownerId(),
                ASSIGNMENT.facilityId(),
                ASSIGNMENT.moves(),
                ASSIGNMENT.dispatchBy(),
                ASSIGNMENT.releasePriority(),
                ASSIGNMENT.assignedAt());
    }
}
