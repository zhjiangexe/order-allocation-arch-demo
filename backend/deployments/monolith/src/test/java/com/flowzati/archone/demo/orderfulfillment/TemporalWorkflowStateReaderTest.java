package com.flowzati.archone.demo.orderfulfillment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flowzati.archone.demo.orderfulfillment.result.WorkflowQueryStatus;
import com.flowzati.archone.demo.orderfulfillment.service.TemporalWorkflowStateReader;
import com.flowzati.archone.orchestration.contract.workflow.order.OrderFulfillmentWorkflow;
import com.flowzati.archone.orchestration.contract.workflow.order.result.OrderFulfillmentSnapshot;
import io.grpc.Status;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowQueryRejectedException;
import io.temporal.client.WorkflowServiceException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class TemporalWorkflowStateReaderTest {
    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final WorkflowExecution EXECUTION = WorkflowExecution.newBuilder()
            .setWorkflowId(OrderFulfillmentWorkflow.workflowId(ORDER_ID))
            .build();
    private final WorkflowClient client = mock(WorkflowClient.class);
    private final OrderFulfillmentWorkflow workflow = mock(OrderFulfillmentWorkflow.class);
    private final TemporalWorkflowStateReader reader = new TemporalWorkflowStateReader(client);

    @BeforeEach
    void stubWorkflow() {
        when(client.newWorkflowStub(OrderFulfillmentWorkflow.class, EXECUTION.getWorkflowId()))
                .thenReturn(workflow);
    }

    @Test
    void returnsSnapshot() {
        OrderFulfillmentSnapshot snapshot = mock(OrderFulfillmentSnapshot.class);
        when(workflow.state()).thenReturn(snapshot);
        var result = reader.find(ORDER_ID);
        assertThat(result.status()).isEqualTo(WorkflowQueryStatus.AVAILABLE);
        assertThat(result.snapshot()).isSameAs(snapshot);
    }

    @Test
    void distinguishesNotFoundFromServiceOutage() {
        when(workflow.state())
                .thenThrow(new WorkflowNotFoundException(EXECUTION, null, Status.NOT_FOUND.asRuntimeException()));
        var result = reader.find(ORDER_ID);
        assertThat(result.status()).isEqualTo(WorkflowQueryStatus.NOT_FOUND);
        assertThat(result.snapshot()).isNull();
    }

    @ParameterizedTest
    @EnumSource(
            value = Status.Code.class,
            names = {"UNAVAILABLE", "DEADLINE_EXCEEDED"})
    void reportsExpectedTransportFailuresAsUnavailable(Status.Code code) {
        when(workflow.state())
                .thenThrow(new WorkflowServiceException(
                        EXECUTION, null, Status.fromCode(code).asRuntimeException()));
        assertThat(reader.find(ORDER_ID).status()).isEqualTo(WorkflowQueryStatus.UNAVAILABLE);
    }

    @Test
    void propagatesRemoteQueryRejection() {
        var rejected = new WorkflowQueryRejectedException(EXECUTION, null, null);
        when(workflow.state()).thenThrow(rejected);
        assertThatThrownBy(() -> reader.find(ORDER_ID)).isSameAs(rejected);
    }

    @ParameterizedTest
    @EnumSource(
            value = Status.Code.class,
            names = {"RESOURCE_EXHAUSTED", "ABORTED", "CANCELLED", "INTERNAL", "INVALID_ARGUMENT"})
    void propagatesOtherServiceErrors(Status.Code code) {
        var failure = new WorkflowServiceException(
                EXECUTION, null, Status.fromCode(code).asRuntimeException());
        when(workflow.state()).thenThrow(failure);
        assertThatThrownBy(() -> reader.find(ORDER_ID)).isSameAs(failure);
    }

    @Test
    void doesNotHideUnexpectedClientOrSerializationFailures() {
        RuntimeException bug = new IllegalStateException("broken converter");
        WorkflowServiceException wrapped = new WorkflowServiceException(EXECUTION, null, bug);
        when(workflow.state()).thenThrow(wrapped);
        assertThatThrownBy(() -> reader.find(ORDER_ID)).isSameAs(wrapped);
        doThrow(bug).when(workflow).state();
        assertThatThrownBy(() -> reader.find(ORDER_ID)).isSameAs(bug);
    }

    @Test
    void rejectsNullSnapshotInsteadOfClaimingAvailable() {
        assertThatThrownBy(() -> reader.find(ORDER_ID)).isInstanceOf(IllegalArgumentException.class);
    }
}
