package com.flowzati.archone.orderfulfillment.orchestration.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowzati.archone.foundation.error.DomainConflictException;
import com.flowzati.archone.foundation.error.ErrorCode;
import com.flowzati.archone.orchestration.contract.workflow.order.OrderFulfillmentWorkflow;
import com.flowzati.archone.orchestration.contract.workflow.order.result.CancellationRequestResult;
import com.flowzati.archone.orchestration.contract.workflow.order.result.CancellationRequestStatus;
import com.flowzati.archone.orderfulfillment.application.FulfillmentCancellationResult;
import com.flowzati.archone.orderfulfillment.application.FulfillmentCancellationStatus;
import com.flowzati.archone.orderfulfillment.application.invocation.FulfillmentCancellationCommand;
import com.flowzati.archone.ordering.api.cancellation.OrderCancellationApi;
import com.flowzati.archone.ordering.api.cancellation.OrderCancellationAssessment;
import io.temporal.client.WorkflowClient;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TemporalCancellationRequestCoordinatorTest {

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-7000-8000-000000000002");

    @ParameterizedTest
    @CsvSource({
        "ACCEPTED, ACCEPTED",
        "ALREADY_REQUESTED, ALREADY_REQUESTED",
        "ALREADY_CANCELLED, ALREADY_CANCELLED",
        "REJECTED, REJECTED",
        "CONFLICT, CONFLICT"
    })
    @DisplayName("temporal mode 應把 HTTP request 送進既有 Workflow Update，而不是直接改 context")
    void shouldDelegateToWorkflowUpdateAndPreserveResult(
            CancellationRequestStatus workflowStatus, FulfillmentCancellationStatus expectedStatus) {
        UUID effectiveRequestId = UUID.fromString("00000000-0000-7000-8000-000000000003");
        OrderCancellationApi orderCancellationApi = mock(OrderCancellationApi.class);
        WorkflowClient workflowClient = mock(WorkflowClient.class);
        OrderFulfillmentWorkflow workflow = mock(OrderFulfillmentWorkflow.class);
        when(orderCancellationApi.assess(any())).thenReturn(OrderCancellationAssessment.CANCELLABLE);
        when(workflowClient.newWorkflowStub(
                        OrderFulfillmentWorkflow.class, OrderFulfillmentWorkflow.workflowId(ORDER_ID)))
                .thenReturn(workflow);
        when(workflow.requestCancellation(any()))
                .thenReturn(new CancellationRequestResult(workflowStatus, effectiveRequestId));
        TemporalCancellationRequestCoordinator coordinator =
                new TemporalCancellationRequestCoordinator(orderCancellationApi, workflowClient);

        FulfillmentCancellationResult result = coordinator.request(request());

        assertThat(result.status()).isEqualTo(expectedStatus);
        assertThat(result.effectiveRequestId()).isEqualTo(effectiveRequestId);
    }

    @Test
    @DisplayName("已完成取消以 request id 與 reason 辨識重送，不把實際 cancelledAt 當成 requestedAt")
    void shouldRecognizeACommittedRequestAfterAsynchronousCancellation() {
        OrderCancellationApi orderCancellationApi = mock(OrderCancellationApi.class);
        WorkflowClient workflowClient = mock(WorkflowClient.class);
        when(orderCancellationApi.assess(any())).thenReturn(OrderCancellationAssessment.ALREADY_CANCELLED);
        TemporalCancellationRequestCoordinator coordinator =
                new TemporalCancellationRequestCoordinator(orderCancellationApi, workflowClient);

        FulfillmentCancellationResult result = coordinator.request(request());

        assertThat(result.status()).isEqualTo(FulfillmentCancellationStatus.ALREADY_CANCELLED);
        verifyNoInteractions(workflowClient);
    }

    @Test
    @DisplayName("已完成取消仍拒絕不同的 immutable request")
    void shouldRejectADifferentCommittedRequest() {
        OrderCancellationApi orderCancellationApi = mock(OrderCancellationApi.class);
        ErrorCode conflictCode = () -> "ORDER_CANCELLATION_REQUEST_CONFLICT";
        when(orderCancellationApi.assess(any()))
                .thenThrow(new DomainConflictException(conflictCode, "Different cancellation request"));
        TemporalCancellationRequestCoordinator coordinator =
                new TemporalCancellationRequestCoordinator(orderCancellationApi, mock(WorkflowClient.class));

        assertThatThrownBy(() -> coordinator.request(new FulfillmentCancellationCommand(
                        UUID.randomUUID(), ORDER_ID, Instant.parse("2026-08-20T08:00:00Z"), "Customer changed mind")))
                .isInstanceOfSatisfying(
                        DomainConflictException.class,
                        exception -> assertThat(exception.errorCode().value())
                                .isEqualTo("ORDER_CANCELLATION_REQUEST_CONFLICT"));
    }

    private static FulfillmentCancellationCommand request() {
        return new FulfillmentCancellationCommand(
                REQUEST_ID, ORDER_ID, Instant.parse("2026-08-20T08:00:00Z"), "Customer changed mind");
    }
}
