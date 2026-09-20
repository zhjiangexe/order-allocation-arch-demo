package com.flowzati.archone.orderfulfillment.orchestration.temporal;

import com.flowzati.archone.foundation.error.ApplicationConflictException;
import com.flowzati.archone.orchestration.contract.workflow.order.OrderFulfillmentWorkflow;
import com.flowzati.archone.orchestration.contract.workflow.order.invocation.CancellationRequestInput;
import com.flowzati.archone.orchestration.contract.workflow.order.result.CancellationRequestResult;
import com.flowzati.archone.orderfulfillment.application.CancellationRequestCoordinator;
import com.flowzati.archone.orderfulfillment.application.FulfillmentCancellationResult;
import com.flowzati.archone.orderfulfillment.application.FulfillmentCancellationStatus;
import com.flowzati.archone.orderfulfillment.application.invocation.FulfillmentCancellationCommand;
import com.flowzati.archone.ordering.api.cancellation.OrderCancellationApi;
import com.flowzati.archone.ordering.api.cancellation.OrderCancellationAssessment;
import com.flowzati.archone.ordering.api.cancellation.OrderCancellationRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Temporal mode 唯一的取消 command adapter；WMS 與 Ordering 決策都留在既有 Workflow 主線。 */
@Component
@ConditionalOnProperty(name = "archone.fulfillment.orchestration-mode", havingValue = "temporal")
public class TemporalCancellationRequestCoordinator implements CancellationRequestCoordinator {

    private final OrderCancellationApi orderCancellationApi;
    private final WorkflowClient workflowClient;

    public TemporalCancellationRequestCoordinator(
            OrderCancellationApi orderCancellationApi, WorkflowClient workflowClient) {
        this.orderCancellationApi = orderCancellationApi;
        this.workflowClient = workflowClient;
    }

    @Override
    public FulfillmentCancellationResult request(FulfillmentCancellationCommand request) {
        OrderCancellationAssessment assessment = orderCancellationApi.assess(new OrderCancellationRequest(
                request.requestId(), request.orderId(), request.requestedAt(), request.reason()));
        if (assessment == OrderCancellationAssessment.ALREADY_CANCELLED) {
            return new FulfillmentCancellationResult(
                    FulfillmentCancellationStatus.ALREADY_CANCELLED, request.requestId());
        }
        if (assessment == OrderCancellationAssessment.FULFILLED) {
            return new FulfillmentCancellationResult(FulfillmentCancellationStatus.REJECTED, request.requestId());
        }

        OrderFulfillmentWorkflow workflow = workflowClient.newWorkflowStub(
                OrderFulfillmentWorkflow.class, OrderFulfillmentWorkflow.workflowId(request.orderId()));
        try {
            CancellationRequestResult acknowledgement = workflow.requestCancellation(new CancellationRequestInput(
                    request.requestId(), request.orderId(), request.requestedAt(), request.reason()));
            return map(acknowledgement);
        } catch (WorkflowNotFoundException exception) {
            throw new ApplicationConflictException(
                    TemporalCancellationErrorCode.WORKFLOW_UNAVAILABLE,
                    "Fulfillment Workflow has not started for Order: " + request.orderId(),
                    exception);
        }
    }

    private static FulfillmentCancellationResult map(CancellationRequestResult acknowledgement) {
        FulfillmentCancellationStatus status =
                switch (acknowledgement.status()) {
                    case ACCEPTED -> FulfillmentCancellationStatus.ACCEPTED;
                    case ALREADY_REQUESTED -> FulfillmentCancellationStatus.ALREADY_REQUESTED;
                    case ALREADY_CANCELLED -> FulfillmentCancellationStatus.ALREADY_CANCELLED;
                    case REJECTED -> FulfillmentCancellationStatus.REJECTED;
                    case CONFLICT -> FulfillmentCancellationStatus.CONFLICT;
                };
        return new FulfillmentCancellationResult(status, acknowledgement.effectiveRequestId());
    }
}
