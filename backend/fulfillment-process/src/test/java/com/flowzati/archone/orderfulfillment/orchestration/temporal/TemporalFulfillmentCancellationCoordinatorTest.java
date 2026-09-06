package com.flowzati.archone.orderfulfillment.orchestration.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowzati.archone.foundation.error.DomainConflictException;
import com.flowzati.archone.orchestration.contract.workflow.order.OrderFulfillmentWorkflow;
import com.flowzati.archone.orchestration.contract.workflow.order.result.CancellationRequestResult;
import com.flowzati.archone.orchestration.contract.workflow.order.result.CancellationRequestStatus;
import com.flowzati.archone.orderfulfillment.application.FulfillmentCancellationRequest;
import com.flowzati.archone.orderfulfillment.application.FulfillmentCancellationResult;
import com.flowzati.archone.orderfulfillment.application.FulfillmentCancellationStatus;
import com.flowzati.archone.ordering.application.usecase.GetOrderUsecase;
import com.flowzati.archone.ordering.domain.aggregate.Order;
import com.flowzati.archone.ordering.domain.error.OrderErrorCode;
import com.flowzati.archone.ordering.domain.type.OrderStatus;
import io.temporal.client.WorkflowClient;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TemporalFulfillmentCancellationCoordinatorTest {

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-7000-8000-000000000002");

    @ParameterizedTest
    @CsvSource({
        "ACCEPTED, ACCEPTED, Cancellation request accepted",
        "ALREADY_REQUESTED, ALREADY_REQUESTED, A cancellation request is already being coordinated",
        "ALREADY_CANCELLED, ALREADY_CANCELLED, Order cancellation is already committed",
        "REJECTED, REJECTED, Shipment cancellation is no longer available"
    })
    @DisplayName("temporal mode 應把 HTTP request 送進既有 Workflow Update，而不是直接改 context")
    void shouldDelegateToWorkflowUpdateAndPreserveResponseDetails(
            CancellationRequestStatus workflowStatus,
            FulfillmentCancellationStatus expectedStatus,
            String expectedDetail) {
        UUID effectiveRequestId = UUID.fromString("00000000-0000-7000-8000-000000000003");
        GetOrderUsecase getOrderUsecase = mock(GetOrderUsecase.class);
        WorkflowClient workflowClient = mock(WorkflowClient.class);
        OrderFulfillmentWorkflow workflow = mock(OrderFulfillmentWorkflow.class);
        Order order = mock(Order.class);
        when(order.getStatus()).thenReturn(OrderStatus.ALLOCATED);
        when(getOrderUsecase.getOrder(ORDER_ID)).thenReturn(order);
        when(workflowClient.newWorkflowStub(
                        OrderFulfillmentWorkflow.class, OrderFulfillmentWorkflow.workflowId(ORDER_ID)))
                .thenReturn(workflow);
        when(workflow.requestCancellation(any()))
                .thenReturn(new CancellationRequestResult(workflowStatus, effectiveRequestId));
        TemporalFulfillmentCancellationCoordinator coordinator =
                new TemporalFulfillmentCancellationCoordinator(getOrderUsecase, workflowClient);

        FulfillmentCancellationResult result = coordinator.request(new FulfillmentCancellationRequest(
                REQUEST_ID, ORDER_ID, Instant.parse("2026-08-20T08:00:00Z"), "Customer changed mind"));

        assertThat(result.status()).isEqualTo(expectedStatus);
        assertThat(result.effectiveRequestId()).isEqualTo(effectiveRequestId);
        assertThat(result.detail()).isEqualTo(expectedDetail);
    }

    @Test
    @DisplayName("已完成取消以 request id 與 reason 辨識重送，不把實際 cancelledAt 當成 requestedAt")
    void shouldRecognizeACommittedRequestAfterAsynchronousCancellation() {
        GetOrderUsecase getOrderUsecase = mock(GetOrderUsecase.class);
        WorkflowClient workflowClient = mock(WorkflowClient.class);
        Order order = mock(Order.class);
        when(order.getStatus()).thenReturn(OrderStatus.CANCELLED);
        when(order.getCancellationRequestId()).thenReturn(REQUEST_ID);
        when(order.getCancellationReason()).thenReturn("Customer changed mind");
        when(getOrderUsecase.getOrder(ORDER_ID)).thenReturn(order);
        TemporalFulfillmentCancellationCoordinator coordinator =
                new TemporalFulfillmentCancellationCoordinator(getOrderUsecase, workflowClient);

        FulfillmentCancellationResult result = coordinator.request(new FulfillmentCancellationRequest(
                REQUEST_ID, ORDER_ID, Instant.parse("2026-08-20T08:00:00Z"), "Customer changed mind"));

        assertThat(result.status()).isEqualTo(FulfillmentCancellationStatus.ALREADY_CANCELLED);
        verifyNoInteractions(workflowClient);
    }

    @Test
    @DisplayName("已完成取消仍拒絕不同的 immutable request")
    void shouldRejectADifferentCommittedRequest() {
        GetOrderUsecase getOrderUsecase = mock(GetOrderUsecase.class);
        Order order = mock(Order.class);
        when(order.getStatus()).thenReturn(OrderStatus.CANCELLED);
        when(order.getCancellationRequestId()).thenReturn(REQUEST_ID);
        when(order.getCancellationReason()).thenReturn("Customer changed mind");
        when(getOrderUsecase.getOrder(ORDER_ID)).thenReturn(order);
        TemporalFulfillmentCancellationCoordinator coordinator =
                new TemporalFulfillmentCancellationCoordinator(getOrderUsecase, mock(WorkflowClient.class));

        assertThatThrownBy(() -> coordinator.request(new FulfillmentCancellationRequest(
                        UUID.randomUUID(), ORDER_ID, Instant.parse("2026-08-20T08:00:00Z"), "Customer changed mind")))
                .isInstanceOfSatisfying(
                        DomainConflictException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(OrderErrorCode.CANCELLATION_REQUEST_CONFLICT));
    }
}
