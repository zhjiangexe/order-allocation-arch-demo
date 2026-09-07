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

class StockOperationAssignmentCheckpointTest {

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
        StockOperationAssignmentCheckpoint checkpoint = new StockOperationAssignmentCheckpoint();

        assertThat(checkpoint.canReceiveAssignment()).isFalse();

        checkpoint.markRequested();

        assertThat(checkpoint.canReceiveAssignment()).isTrue();

        checkpoint.recordAssigned(ASSIGNMENT);

        assertThat(checkpoint.canReceiveAssignment()).isTrue();
        assertThat(checkpoint.assignmentSnapshot()).isSameAs(ASSIGNMENT);
    }

    @Test
    void updatesTheSameCheckpointFromNotRequestedToCommitted() {
        StockOperationAssignmentCheckpoint checkpoint = new StockOperationAssignmentCheckpoint();

        assertThat(checkpoint.state()).isEqualTo(OrderFulfillmentAllocationState.NOT_REQUESTED);
        assertThat(checkpoint.assignmentSnapshot()).isNull();
        assertThat(checkpoint.stockOperationId()).isNull();

        checkpoint.markRequested();

        assertThat(checkpoint.isRequested()).isTrue();
        assertThat(checkpoint.assignmentSnapshot()).isNull();

        checkpoint.recordAssigned(ASSIGNMENT);

        assertThat(checkpoint.isCommitted()).isTrue();
        assertThat(checkpoint.isRequested()).isFalse();
        assertThat(checkpoint.assignmentSnapshot()).isSameAs(ASSIGNMENT);
        assertThat(checkpoint.stockOperationId()).isEqualTo(ASSIGNMENT.stockOperationId());
    }

    @Test
    void acceptsIdenticalAssignmentWithoutReplacingTheAcceptedFact() {
        StockOperationAssignmentCheckpoint checkpoint = new StockOperationAssignmentCheckpoint();
        checkpoint.markRequested();
        checkpoint.recordAssigned(ASSIGNMENT);
        StockOperationAssignedInput replay = assignmentWithId(ASSIGNMENT.stockOperationId());

        checkpoint.recordAssigned(replay);

        assertThat(checkpoint.isCommitted()).isTrue();
        assertThat(checkpoint.assignmentSnapshot()).isSameAs(ASSIGNMENT);
    }

    @Test
    void rejectsConflictingAssignmentWithoutChangingState() {
        StockOperationAssignmentCheckpoint checkpoint = new StockOperationAssignmentCheckpoint();
        checkpoint.markRequested();
        checkpoint.recordAssigned(ASSIGNMENT);

        assertThatThrownBy(() -> checkpoint.recordAssigned(assignmentWithId(UUID.randomUUID())))
                .isInstanceOf(ApplicationFailure.class)
                .hasMessageContaining("conflicting stock operation assignment facts");

        assertThat(checkpoint.isCommitted()).isTrue();
        assertThat(checkpoint.assignmentSnapshot()).isSameAs(ASSIGNMENT);
        assertThat(checkpoint.stockOperationId()).isEqualTo(ASSIGNMENT.stockOperationId());
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
