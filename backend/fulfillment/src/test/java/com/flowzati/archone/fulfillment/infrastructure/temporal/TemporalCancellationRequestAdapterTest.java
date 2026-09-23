package com.flowzati.archone.fulfillment.infrastructure.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flowzati.archone.fulfillment.application.invocation.FulfillmentCancellationCommand;
import com.flowzati.archone.fulfillment.application.result.FulfillmentCancellationResult;
import com.flowzati.archone.fulfillment.application.state.FulfillmentCancellationStatus;
import com.flowzati.archone.orchestration.contract.workflow.order.OrderFulfillmentWorkflow;
import com.flowzati.archone.orchestration.contract.workflow.order.result.CancellationRequestResult;
import com.flowzati.archone.orchestration.contract.workflow.order.result.CancellationRequestStatus;
import io.temporal.client.WorkflowClient;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TemporalCancellationRequestAdapterTest {

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
    @DisplayName("Temporal driver 應把 application command 送進 Workflow Update 並轉換結果")
    void shouldDelegateToWorkflowUpdateAndPreserveResult(
            CancellationRequestStatus workflowStatus, FulfillmentCancellationStatus expectedStatus) {
        UUID effectiveRequestId = UUID.fromString("00000000-0000-7000-8000-000000000003");
        WorkflowClient workflowClient = mock(WorkflowClient.class);
        OrderFulfillmentWorkflow workflow = mock(OrderFulfillmentWorkflow.class);
        when(workflowClient.newWorkflowStub(
                        OrderFulfillmentWorkflow.class, OrderFulfillmentWorkflow.workflowId(ORDER_ID)))
                .thenReturn(workflow);
        when(workflow.requestCancellation(any()))
                .thenReturn(new CancellationRequestResult(workflowStatus, effectiveRequestId));
        TemporalCancellationRequestAdapter adapter = new TemporalCancellationRequestAdapter(workflowClient);

        FulfillmentCancellationResult result = adapter.request(request());

        assertThat(result.status()).isEqualTo(expectedStatus);
        assertThat(result.effectiveRequestId()).isEqualTo(effectiveRequestId);
    }

    private static FulfillmentCancellationCommand request() {
        return new FulfillmentCancellationCommand(
                REQUEST_ID, ORDER_ID, Instant.parse("2026-08-20T08:00:00Z"), "Customer changed mind");
    }
}
